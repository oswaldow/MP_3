package com.learnlayout.mp_3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.util.UnstableApi
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Ecualizador de 5 bandas implementado en software (filtros biquad
 * "peaking" en cascada para las 3 bandas medias, y "shelf" para las 2
 * bandas extremas, formulas del Audio EQ Cookbook de RBJ), que se
 * inyecta directo en la cadena de AudioProcessor de ExoPlayer (ver
 * EqAudioSinkRenderersFactory / MusicService.buildPlayer()).
 *
 * Por que existe esto en vez de usar android.media.audiofx.Equalizer:
 * en varios dispositivos (HyperOS/MIUI del POCO F6 entre ellos) el
 * motor de efectos de audio del sistema RECHAZA la creacion de
 * Equalizer/BassBoost para apps de terceros con Error -3, sin importar
 * el timing. Procesando el PCM nosotros mismos, el ecualizador
 * funciona siempre, en cualquier dispositivo, porque no depende de
 * ningun servicio del sistema.
 *
 * El estado "real" (ganancias por banda, enabled/disabled) vive en el
 * companion object -compartido por TODAS las instancias de este
 * processor que cree MusicService a lo largo de la vida de la app, una
 * por cada ExoPlayer nuevo-. Cada instancia mantiene su propio estado
 * de filtro (x1,x2,y1,y2 por canal) porque eso si es propio de cada
 * stream de audio.
 */
@UnstableApi
class SoftwareEqualizerProcessor : AudioProcessor {

    companion object {
        // 5 bandas tipicas de un ecualizador de telefono.
        val CENTER_FREQS_HZ = intArrayOf(60, 230, 910, 3600, 14000)
        const val NUM_BANDS = 5

        const val MIN_GAIN_MILLIBEL = -1500
        const val MAX_GAIN_MILLIBEL = 1500

        private const val Q = 1.0

        // DEBUG: filtrar en Logcat con  adb logcat -s MP3_EQ
        private const val TAG_EQ = "MP3_EQ"

        // Para no inundar Logcat: solo logueamos 1 de cada N buffers
        // procesados en queueInput, y logueamos SIEMPRE que cambia un valor.
        @Volatile private var logCounter = 0

        // Ganancia por banda en milibeles (100 mB = 1 dB), y flag global
        // de enabled/disabled. @Volatile: se leen desde el hilo de audio
        // y se escriben desde el hilo principal (UI).
        @Volatile private var bandGainsMillibel = IntArray(NUM_BANDS)
        @Volatile private var masterEnabled = false

        // Se incrementa cada vez que cambia una ganancia o el enabled,
        // para que las instancias existentes sepan que tienen que
        // recalcular sus coeficientes en vez de recomputar en cada
        // muestra.
        @Volatile private var configVersion = 0

        fun setBandGainMillibel(band: Int, gainMillibel: Int) {
            if (band !in 0 until NUM_BANDS) return
            val clamped = gainMillibel.coerceIn(MIN_GAIN_MILLIBEL, MAX_GAIN_MILLIBEL)
            val newArray = bandGainsMillibel.copyOf()
            newArray[band] = clamped
            bandGainsMillibel = newArray
            configVersion++
            Log.d(TAG_EQ, "setBandGainMillibel: band=$band gain=$clamped mB masterEnabled=$masterEnabled configVersion=$configVersion")
        }

        fun getBandGainMillibel(band: Int): Int {
            if (band !in 0 until NUM_BANDS) return 0
            return bandGainsMillibel[band]
        }

        fun setMasterEnabled(enabled: Boolean) {
            masterEnabled = enabled
            configVersion++
            Log.d(TAG_EQ, "setMasterEnabled: enabled=$enabled configVersion=$configVersion")
        }

        fun isMasterEnabled(): Boolean = masterEnabled

        fun resetAllBands() {
            bandGainsMillibel = IntArray(NUM_BANDS)
            configVersion++
        }
    }

    private data class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private class ChannelBandState {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
    }

    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET

    private var coeffs: Array<Coeffs> = emptyArray()
    private var channelStates: Array<Array<ChannelBandState>> = emptyArray()
    private var appliedVersion = -1

    // Atenuacion global aplicada ANTES de filtrar, para dejar headroom
    // cuando hay varias bandas boosteadas a la vez (ver computeHeadroomGain).
    // 1.0 = sin atenuar.
    private var preGainLinear = 1.0

    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            Log.e(TAG_EQ, "configure: formato NO soportado, encoding=${inputAudioFormat.encoding} -> UnhandledAudioFormatException (el EQ queda AFUERA de la cadena)")
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        channelStates = Array(inputAudioFormat.channelCount) {
            Array(NUM_BANDS) { ChannelBandState() }
        }
        appliedVersion = -1 // fuerza a recalcular coeficientes con el nuevo sampleRate
        Log.d(TAG_EQ, "configure: OK sampleRate=${inputAudioFormat.sampleRate} channels=${inputAudioFormat.channelCount} instance=${System.identityHashCode(this)}")
        return outputAudioFormat
    }

    override fun isActive(): Boolean {
        val active = outputAudioFormat != AudioFormat.NOT_SET
        Log.d(TAG_EQ, "isActive: $active instance=${System.identityHashCode(this)}")
        return active
    }

    private fun recomputeCoeffsIfNeeded() {
        if (appliedVersion == configVersion) return
        val sampleRate = inputAudioFormat.sampleRate
        val gains = bandGainsMillibel

        // Bandas extremas (Graves y Brillo) usan filtro shelf: mueven TODO
        // el rango por debajo/encima de la frecuencia, no solo una campana
        // angosta ahi. Se siente mucho mas parecido a lo que la gente
        // espera de "graves"/"agudos" en un ecualizador de telefono. Las
        // 3 bandas del medio siguen siendo peaking (campana), que es lo
        // correcto para frecuencias intermedias.
        coeffs = Array(NUM_BANDS) { band ->
            val gainDb = gains[band] / 100.0
            when (band) {
                0 -> computeLowShelf(CENTER_FREQS_HZ[band].toDouble(), sampleRate, gainDb)
                NUM_BANDS - 1 -> computeHighShelf(CENTER_FREQS_HZ[band].toDouble(), sampleRate, gainDb)
                else -> computeBiquadPeaking(CENTER_FREQS_HZ[band].toDouble(), sampleRate, gainDb, Q)
            }
        }

        preGainLinear = computeHeadroomGain(gains)
        appliedVersion = configVersion
        val preGainDb = 20.0 * log10(preGainLinear)
        Log.d(TAG_EQ, "recomputeCoeffsIfNeeded: recalculado, appliedVersion=$appliedVersion gains=${gains.toList()} preGainDb=$preGainDb instance=${System.identityHashCode(this)}")
    }

    /**
     * Si varias bandas estan boosteadas al mismo tiempo, sus picos se
     * pueden sumar en ciertas frecuencias y superar el rango de 16 bits.
     * Antes eso se resolvia con un recorte seco (hard clip) que suena
     * distorsionado. Ahora, en vez de eso, se le resta un poco de volumen
     * a TODA la senal antes de filtrar (headroom), proporcional a cuanto
     * boost total se esta pidiendo, para que el pico ya casi no llegue a
     * necesitar el limiter de la funcion softLimit().
     */
    private fun computeHeadroomGain(gainsMillibel: IntArray): Double {
        val totalBoostDb = gainsMillibel.filter { it > 0 }.sumOf { it / 100.0 }
        val headroomDb = (totalBoostDb * 0.5).coerceAtMost(12.0)
        return 10.0.pow(-headroomDb / 20.0)
    }

    private fun computeBiquadPeaking(freqHz: Double, sampleRate: Int, gainDb: Double, q: Double): Coeffs {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosw0 = cos(w0)

        val b0 = 1 + alpha * a
        val b1 = -2 * cosw0
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cosw0
        val a2 = 1 - alpha / a

        return Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    // Formulas del Audio EQ Cookbook de RBJ para shelf filters, con
    // pendiente de estante S=1.0 (la mas comun/pareja para EQs de audio).
    private fun computeLowShelf(freqHz: Double, sampleRate: Int, gainDb: Double): Coeffs {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val shelfSlope = 1.0
        val alpha = sinw0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / shelfSlope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1) - (a - 1) * cosw0 + twoSqrtAAlpha)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cosw0)
        val b2 = a * ((a + 1) - (a - 1) * cosw0 - twoSqrtAAlpha)
        val a0 = (a + 1) + (a - 1) * cosw0 + twoSqrtAAlpha
        val a1 = -2 * ((a - 1) + (a + 1) * cosw0)
        val a2 = (a + 1) + (a - 1) * cosw0 - twoSqrtAAlpha

        return Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun computeHighShelf(freqHz: Double, sampleRate: Int, gainDb: Double): Coeffs {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val shelfSlope = 1.0
        val alpha = sinw0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / shelfSlope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1) + (a - 1) * cosw0 + twoSqrtAAlpha)
        val b1 = -2 * a * ((a - 1) + (a + 1) * cosw0)
        val b2 = a * ((a + 1) + (a - 1) * cosw0 - twoSqrtAAlpha)
        val a0 = (a + 1) - (a - 1) * cosw0 + twoSqrtAAlpha
        val a1 = 2 * ((a - 1) - (a + 1) * cosw0)
        val a2 = (a + 1) - (a - 1) * cosw0 - twoSqrtAAlpha

        return Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun processSample(channel: Int, input: Double): Double {
        var x = input * preGainLinear
        val states = channelStates[channel]
        for (band in 0 until NUM_BANDS) {
            val c = coeffs[band]
            val s = states[band]
            val y = c.b0 * x + c.b1 * s.x1 + c.b2 * s.x2 - c.a1 * s.y1 - c.a2 * s.y2
            s.x2 = s.x1
            s.x1 = x
            s.y2 = s.y1
            s.y1 = y
            x = y
        }
        return x
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val channelCount = inputAudioFormat.channelCount
        val out = replaceOutputBuffer(size)

        // DEBUG: solo 1 de cada 200 buffers (~cada par de segundos con
        // buffers tipicos) para no inundar Logcat.
        logCounter++
        if (logCounter % 200 == 0) {
            Log.d(TAG_EQ, "queueInput: buffer #$logCounter size=$size masterEnabled=$masterEnabled instance=${System.identityHashCode(this)}")
        }

        if (!masterEnabled) {
            // Bypass: copia directa, sin gastar CPU en filtrado.
            out.put(inputBuffer)
        } else {
            recomputeCoeffsIfNeeded()
            val src = inputBuffer.order(ByteOrder.nativeOrder())
            var channel = 0
            while (src.remaining() >= 2) {
                val sampleIn = src.short.toDouble()
                val sampleOut = softLimit(processSample(channel, sampleIn))
                out.putShort(sampleOut.toInt().toShort())
                channel = (channel + 1) % channelCount
            }
            inputBuffer.position(inputBuffer.limit())
        }
        out.flip()
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

    // Red de seguridad final: en vez de recortar seco (lo que sonaba
    // "feo"/distorsionado), si una muestra se acerca al limite de 16 bits
    // la comprime suave con una curva tanh. Por debajo del umbral no toca
    // nada, asi que en volumen normal es completamente transparente.
    private fun softLimit(sample: Double): Double {
        val threshold = 28000.0
        val ceilingPos = 32767.0
        val ceilingNeg = -32768.0

        return when {
            sample > threshold -> {
                val range = ceilingPos - threshold
                (threshold + range * tanh((sample - threshold) / range)).coerceAtMost(ceilingPos)
            }
            sample < -threshold -> {
                val range = -ceilingNeg - threshold
                (-threshold + range * tanh((sample + threshold) / range)).coerceAtLeast(ceilingNeg)
            }
            else -> sample
        }
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
        channelStates.forEach { bands ->
            bands.forEach { it.x1 = 0.0; it.x2 = 0.0; it.y1 = 0.0; it.y2 = 0.0 }
        }
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}