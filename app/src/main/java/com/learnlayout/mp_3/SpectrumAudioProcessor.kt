package com.learnlayout.mp_3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * El estado publicado (magnitudes) vive en el companion object, igual
 * que bandGainsMillibel en SoftwareEqualizerProcessor: se escribe desde
 * el hilo de audio (queueInput) y se lee desde el hilo de UI
 * (AudioSpectrumView), por eso es @Volatile y se publica reemplazando
 * la referencia completa del array (nunca mutando in-place).
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

        @Volatile
        private var magnitudes = FloatArray(NUM_BANDS)

        /** Ultimo snapshot de energia por banda, normalizado en [0, 1]. */
        fun getMagnitudes(): FloatArray = magnitudes

        fun bandCount(): Int = NUM_BANDS

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

        // Publicacion atomica: reemplaza la referencia completa, nunca
        // muta el array que el hilo de UI puede estar leyendo.
        magnitudes = next
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
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}