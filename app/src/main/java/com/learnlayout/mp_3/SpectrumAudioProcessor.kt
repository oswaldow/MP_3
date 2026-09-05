package com.learnlayout.mp_3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AudioProcessor de "solo lectura": se mete en la misma cadena que
 * SoftwareEqualizerProcessor (ver EqAudioSinkRenderersFactory /
 * MusicService.buildPlayer()) pero NUNCA modifica el audio, solo lo
 * analiza al vuelo y copia el buffer de entrada a la salida sin tocarlo.
 *
 * Por que un AudioProcessor y no android.media.audiofx.Visualizer: el
 * mismo motivo que ya documenta SoftwareEqualizerProcessor -en varios
 * fabricantes (HyperOS/MIUI, etc.) el motor de efectos de audio del
 * sistema rechaza la creacion de efectos de terceros-, asi que en vez
 * de depender de un servicio del sistema, se analiza el PCM que ya
 * estamos procesando nosotros mismos para el ecualizador.
 *
 * Algoritmo: por cada banda de frecuencia se corre un Goertzel de un
 * solo bin (mas barato que una FFT completa cuando solo hacen falta
 * NUM_BANDS energias puntuales, no el espectro completo) sobre una
 * ventana de WINDOW_SIZE muestras mono (downmix simple del promedio de
 * canales). El resultado se normaliza y se suaviza con ataque rapido /
 * caida lenta para que las barras del visualizador no "tiemblen".
 *
 * IMPORTANTE (fix bug "barras se ven a los tirones con archivos
 * hi-res/FLAC"): el decoder no entrega el PCM en trozos parejos. Un
 * archivo MP3/AAC normal llega en paquetes chicos y frecuentes (~24ms
 * cada uno a 44.1kHz), pero un FLAC hi-res puede llegar en paquetes
 * ~4 veces mas grandes y ~4 veces menos frecuentes (un bloque FLAC
 * tipico son 4096 muestras). Antes, cada paquete grande disparaba
 * varios computeSpectrum() seguidos que se pisaban entre si en una
 * sola referencia @Volatile: la vista solo veia el ultimo, así que
 * las barras se quedaban clavadas ~100ms y despues saltaban de golpe.
 * Ahora cada computeSpectrum() se publica en una cola
 * (snapshotQueue) en vez de pisar un valor unico, y se expone
 * getUpdateIntervalMs() para que quien consuma (AudioSpectrumView)
 * saque un snapshot de la cola a ritmo parejo -uno cada ~23ms a
 * 44.1kHz- sin importar si llegaron todos juntos o de a uno.
 *
 * IMPORTANTE (fix bug "el visualizador va atrasado, no en tiempo
 * real"): la cola por si sola no bastaba. Si el consumidor
 * (AudioSpectrumView) se atrasaba aunque sea una vez -jank de UI,
 * decodificar la caratula, GC, o simplemente estar pausado mientras
 * el panel estaba colapsado- el hilo de audio seguia produciendo
 * snapshots en tiempo real y la cola se llenaba. Como el consumidor
 * jamas sacaba mas de un snapshot por tick, ese atraso quedaba
 * pegado para siempre en vez de recuperarse solo. Se agregan
 * pendingSnapshotCount() (para que el consumidor sepa si el backlog
 * es mas grande de lo esperable por un burst normal de FLAC hi-res,
 * y en ese caso descarte lo viejo) y clearQueue() (para que el
 * consumidor vacie la cola al reanudar despues de una pausa, y no
 * arranque mostrando snapshots de antes de esa pausa).
 *
 * El estado publicado vive en el companion object, igual que
 * bandGainsMillibel en SoftwareEqualizerProcessor: se escribe desde
 * el hilo de audio (queueInput) y se lee desde el hilo de UI
 * (AudioSpectrumView). La cola es thread-safe (ArrayBlockingQueue) y
 * el productor nunca bloquea: si se llena (no deberia pasar en uso
 * normal), descarta el snapshot mas viejo en vez de esperar, para no
 * frenar el hilo de audio.
 */
@UnstableApi
class SpectrumAudioProcessor : AudioProcessor {

    companion object {
        const val NUM_BANDS = 24

        // Tamano de la ventana de analisis en muestras mono. A 44.1kHz son
        // ~23ms por ventana (~43 actualizaciones/seg), suficiente para que
        // el visualizador se sienta reactivo sin gastar CPU de mas.
        private const val WINDOW_SIZE = 1024

        private const val MIN_FREQ_HZ = 40.0
        private const val MAX_FREQ_HZ = 16000.0

        // Suavizado exponencial: sube rapido (ATTACK) para que los golpes
        // se noten, baja lento (RELEASE) para que no parpadee.
        private const val ATTACK = 0.55f
        private const val RELEASE = 0.15f

        // Divisor de normalizacion: magnitud Goertzel esperada para una
        // senal "a todo volumen" en 16 bits. Ajustado a ojo para que las
        // barras usen bien el rango 0..1 con musica normal sin saturar
        // todo el tiempo en el maximo.
        private const val NORMALIZATION_DIVISOR = 4500.0

        // Cuantos snapshots calculados-pero-no-consumidos-todavia se
        // permiten acumular. Con paquetes de PCM grandes (FLAC hi-res)
        // pueden llegar varios computeSpectrum() casi seguidos; esta cola
        // los retiene para que se consuman de a uno, a ritmo parejo, en
        // vez de perderse. 16 da margen holgado (un paquete tipico genera
        // como maximo 4-5 snapshots de una).
        private const val SNAPSHOT_QUEUE_CAPACITY = 16

        @Volatile
        private var magnitudes = FloatArray(NUM_BANDS)

        private val snapshotQueue = ArrayBlockingQueue<FloatArray>(SNAPSHOT_QUEUE_CAPACITY)

        // Intervalo "natural" entre actualizaciones, segun WINDOW_SIZE y el
        // sample rate de la pista actual (recalculado en configure()).
        // Quien consuma la cola deberia sacar un snapshot nuevo cada tantos
        // ms, no uno por frame de UI, para no vaciar de golpe un paquete
        // grande que trajo varios snapshots juntos.
        @Volatile
        private var updateIntervalMs: Long = 23L

        /**
         * Ultimo snapshot calculado, para compatibilidad con quien
         * necesite "el valor actual" sin importarle el ritmo de consumo
         * parejo (por ejemplo un uso puntual, no animado).
         */
        fun getMagnitudes(): FloatArray = magnitudes

        /**
         * Saca el proximo snapshot pendiente de la cola, o null si no hay
         * ninguno todavia. No bloquea. Pensado para llamarse a ritmo
         * pautado por getUpdateIntervalMs(), no una vez por frame de UI.
         */
        fun pollNextSnapshot(): FloatArray? = snapshotQueue.poll()

        /**
         * Cuantos snapshots hay esperando a ser consumidos ahora mismo.
         * Pensado para que el consumidor detecte un backlog "anormal"
         * (mas grande que el que produciria un burst normal de FLAC
         * hi-res) y decida ponerse al dia descartando snapshots viejos,
         * en vez de arrastrar ese atraso para siempre.
         */
        fun pendingSnapshotCount(): Int = snapshotQueue.size

        /**
         * Vacia la cola sin tocar el "ultimo valor" publicado en
         * magnitudes. Pensado para llamarse cuando el consumidor vuelve
         * a arrancar despues de estar parado (panel colapsado, vista
         * oculta, etc.), para no arrastrar snapshots viejos de antes de
         * esa pausa.
         */
        fun clearQueue() {
            snapshotQueue.clear()
        }

        fun bandCount(): Int = NUM_BANDS

        /** Cada cuantos ms conviene sacar un snapshot nuevo de la cola. */
        fun getUpdateIntervalMs(): Long = updateIntervalMs

        // Frecuencias centrales de cada banda, distribuidas en escala
        // logaritmica (asi se ve/siente como un espectrometro real: mas
        // resolucion en graves/medios, que es donde el oido humano y la
        // musica tienen mas informacion).
        private val bandCenterHz: DoubleArray = DoubleArray(NUM_BANDS) { i ->
            val t = if (NUM_BANDS <= 1) 0.0 else i.toDouble() / (NUM_BANDS - 1)
            val logMin = ln(MIN_FREQ_HZ)
            val logMax = ln(MAX_FREQ_HZ)
            exp(logMin + t * (logMax - logMin))
        }
    }

    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET

    private val window = FloatArray(WINDOW_SIZE)
    private var windowPos = 0

    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // No es el formato esperado: nos sacamos solos de la cadena
            // (ExoPlayer sigue funcionando, simplemente sin visualizador).
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        windowPos = 0

        // Pista nueva: recalcular el ritmo "natural" de actualizacion y
        // vaciar la cola para no arrastrar snapshots de la pista anterior.
        updateIntervalMs = ((WINDOW_SIZE.toLong() * 1000L) / inputAudioFormat.sampleRate)
            .coerceAtLeast(1L)
        snapshotQueue.clear()

        return outputAudioFormat
    }

    override fun isActive(): Boolean = outputAudioFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        // Analiza una copia (duplicate() comparte el contenido pero tiene
        // su propia posicion/limite) para no interferir con el paso de
        // "copiar tal cual a la salida" de abajo.
        analyze(inputBuffer.duplicate().order(ByteOrder.nativeOrder()))

        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }

    private fun analyze(src: ByteBuffer) {
        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val frameSizeBytes = 2 * channelCount
        while (src.remaining() >= frameSizeBytes) {
            var sum = 0
            for (c in 0 until channelCount) {
                sum += src.short
            }
            window[windowPos++] = (sum / channelCount).toFloat()
            if (windowPos >= WINDOW_SIZE) {
                computeSpectrum()
                windowPos = 0
            }
        }
    }

    private fun computeSpectrum() {
        val sampleRate = inputAudioFormat.sampleRate
        if (sampleRate <= 0) return

        val n = WINDOW_SIZE
        val previous = magnitudes
        val next = FloatArray(NUM_BANDS)

        for (band in 0 until NUM_BANDS) {
            val freq = bandCenterHz[band]
            val k = (0.5 + (n * freq) / sampleRate).toInt()
            val omega = 2.0 * PI * k / n
            val cosOmega = cos(omega)
            val coeff = 2.0 * cosOmega

            var s1 = 0.0
            var s2 = 0.0
            for (i in 0 until n) {
                val s0 = window[i] + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            val real = s1 - s2 * cosOmega
            val imag = s2 * sin(omega)
            val magnitude = sqrt(real * real + imag * imag) / n

            val target = (magnitude / NORMALIZATION_DIVISOR).coerceIn(0.0, 1.0).toFloat()
            val prevValue = if (band < previous.size) previous[band] else 0f
            val rate = if (target > prevValue) ATTACK else RELEASE
            next[band] = prevValue + (target - prevValue) * rate
        }

        // Publicacion atomica del "valor actual" para compatibilidad, y
        // ademas se encola para consumo pautado (ver getUpdateIntervalMs).
        // El productor (hilo de audio) nunca debe bloquearse: si la cola
        // esta llena, se descarta el snapshot mas viejo en vez de esperar.
        magnitudes = next
        if (!snapshotQueue.offer(next)) {
            snapshotQueue.poll()
            snapshotQueue.offer(next)
        }
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
        return buffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val result = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return result
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        windowPos = 0
        magnitudes = FloatArray(NUM_BANDS)
        snapshotQueue.clear()
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}