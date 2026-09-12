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
 * Ecualizador grafico de 10 bandas, 100% software (AudioProcessor propio
 * dentro de la cadena de Media3). No depende de android.media.audiofx.Equalizer
 * (el DSP nativo del fabricante): con el nativo, el rango real de dB y la
 * calidad del efecto dependian del chip de audio de cada telefono -en
 * equipos de gama media el efecto se sentia debil, y el DSP del fabricante
 * podia sonar sucio/distorsionado al subir varias bandas, sin que la app
 * tuviera ningun control sobre eso-. Con esto, el resultado es identico en
 * cualquier telefono.
 *
 * Dos cambios clave respecto a la version anterior de este mismo archivo:
 *
 * 1) HEADROOM EXACTO EN VEZ DE UNA REGLA FIJA: antes se restaba "la mitad
 *    de la suma de todo el boost positivo" sin importar la forma real de
 *    la curva resultante, lo que atenuaba el efecto incluso subiendo una
 *    sola banda. Ahora se calcula la respuesta en frecuencia REAL de las
 *    10 bandas ya combinadas (computeHeadroomLinear), se busca el pico
 *    mas alto de esa curva, y solo se resta el headroom que hace falta
 *    para que ese pico no sature. Si no hay riesgo de saturar, no se resta
 *    nada: el boost pedido se aplica completo.
 *
 * 2) Q CORRECTO PARA BANDAS SEPARADAS POR OCTAVA: las 10 frecuencias
 *    (31, 62, 125...16k) estan separadas exactamente una octava entre si.
 *    El valor de Q que le corresponde matematicamente a un ancho de banda
 *    de 1 octava es sqrt(2)/(2-1) ~= 1.41 (formula estandar de conversion
 *    ancho de banda -> Q), no 1.0 como tenia antes. Con Q=1 las bandas se
 *    superponian mas de lo necesario entre si, lo que hacia mas impredecible
 *    el resultado al subir varias bandas juntas.
 *
 * El limitador suave (softLimit, con tanh en vez de un recorte seco) se
 * queda como red de seguridad de ultimo recurso: con el headroom exacto
 * casi nunca deberia dispararse, pero cubre picos puntuales que el calculo
 * de headroom (hecho solo en frecuencia, no por muestra) no puede prever,
 * y tambien protege si el preamp manual (PreampAudioProcessor, que corre
 * ANTES que este procesador en la cadena) suma su propia ganancia encima.
 *
 * El preamp manual del usuario YA NO vive aqui: eso lo maneja por separado
 * PreampAudioProcessor. Este procesador solo se encarga de las 10 bandas
 * y de su propio headroom.
 */
@UnstableApi
class SoftwareEqualizerProcessor : AudioProcessor {

    companion object {
        // 10 bandas graficas estandar, separadas exactamente una octava.
        val CENTER_FREQS_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        const val NUM_BANDS = 10

        const val MIN_GAIN_MILLIBEL = -1500
        const val MAX_GAIN_MILLIBEL = 1500

        // Q para un ancho de banda de 1 octava: sqrt(2^BW) / (2^BW - 1) con BW=1.
        // Da una curva combinada mas limpia y previsible que Q=1.0 al subir
        // varias bandas adyacentes a la vez.
        private val BAND_Q = sqrt(2.0) / (2.0.pow(1.0) - 1.0)

        private const val TAG_EQ = "MP3_EQ"

        // Ganancia por banda en milibeles (100 mB = 1 dB), y flag global
        // de enabled/disabled. @Volatile: se leen desde el hilo de audio
        // y se escriben desde el hilo principal (UI).
        @Volatile private var bandGainsMillibel = IntArray(NUM_BANDS)
        @Volatile private var masterEnabled = false

        // Se incrementa cada vez que cambia una ganancia o el enabled,
        // para que las instancias existentes sepan que tienen que
        // recalcular coeficientes y headroom en vez de recomputar en cada
        // muestra.
        @Volatile private var configVersion = 0

        fun setBandGainMillibel(band: Int, gainMillibel: Int) {
            if (band !in 0 until NUM_BANDS) return
            val clamped = gainMillibel.coerceIn(MIN_GAIN_MILLIBEL, MAX_GAIN_MILLIBEL)
            val newArray = bandGainsMillibel.copyOf()
            newArray[band] = clamped
            bandGainsMillibel = newArray
            configVersion++
        }

        fun getBandGainMillibel(band: Int): Int {
            if (band !in 0 until NUM_BANDS) return 0
            return bandGainsMillibel[band]
        }

        fun setMasterEnabled(enabled: Boolean) {
            masterEnabled = enabled
            configVersion++
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

    // Atenuacion global aplicada ANTES de filtrar, calculada a partir del
    // pico real de la curva combinada (ver computeHeadroomLinear). 1.0 =
    // sin atenuar (curva combinada sin picos por encima de 0 dB).
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
        return outputAudioFormat
    }

    override fun isActive(): Boolean {
        return outputAudioFormat != AudioFormat.NOT_SET
    }

    private fun recomputeCoeffsIfNeeded() {
        if (appliedVersion == configVersion) return
        val sampleRate = inputAudioFormat.sampleRate
        val gains = bandGainsMillibel

        // La primera y ultima banda son shelves; las ocho intermedias son
        // peaking. La frecuencia se limita por debajo de Nyquist para evitar
        // coeficientes invalidos en streams con sample rates poco comunes.
        coeffs = Array(NUM_BANDS) { band ->
            val gainDb = gains[band] / 100.0
            val safeFreq = CENTER_FREQS_HZ[band].toDouble()
                .coerceAtMost(sampleRate * 0.45)
                .coerceAtLeast(20.0)
            when (band) {
                0 -> computeLowShelf(safeFreq, sampleRate, gainDb)
                NUM_BANDS - 1 -> computeHighShelf(safeFreq, sampleRate, gainDb)
                else -> computeBiquadPeaking(safeFreq, sampleRate, gainDb, BAND_Q)
            }
        }

        preGainLinear = computeHeadroomLinear(sampleRate, coeffs)
        appliedVersion = configVersion
    }

    /**
     * Muestrea la respuesta en frecuencia de las 10 bandas YA COMBINADAS
     * (en cascada) en puntos espaciados logaritmicamente entre 20 Hz y
     * casi Nyquist, y devuelve la ganancia lineal necesaria para que el
     * pico mas alto de esa curva no pase de 0 dB. Si la curva combinada
     * no supera 0 dB en ningun punto (por ejemplo, todo en 0 o solo hay
     * cortes), devuelve 1.0: no se resta nada que no haga falta.
     */
    private fun computeHeadroomLinear(sampleRate: Int, bandCoeffs: Array<Coeffs>): Double {
        val numPoints = 300
        val minHz = 20.0
        val maxHz = (sampleRate * 0.49).coerceAtLeast(minHz + 1.0)
        val logMin = kotlin.math.ln(minHz)
        val logMax = kotlin.math.ln(maxHz)

        var peakDb = 0.0
        for (i in 0 until numPoints) {
            val t = i.toDouble() / (numPoints - 1)
            val hz = kotlin.math.exp(logMin + (logMax - logMin) * t)
            val w = 2.0 * PI * hz / sampleRate

            var totalDb = 0.0
            for (c in bandCoeffs) {
                totalDb += biquadMagnitudeDb(c, w)
            }
            if (totalDb > peakDb) peakDb = totalDb
        }

        return if (peakDb > 0.0) 10.0.pow(-peakDb / 20.0) else 1.0
    }

    /** Magnitud en dB de un solo biquad a la frecuencia angular [w] (radianes/muestra). */
    private fun biquadMagnitudeDb(c: Coeffs, w: Double): Double {
        val cosw = cos(w)
        val sinw = sin(w)
        val cos2w = cos(2.0 * w)
        val sin2w = sin(2.0 * w)

        val numRe = c.b0 + c.b1 * cosw + c.b2 * cos2w
        val numIm = -c.b1 * sinw - c.b2 * sin2w
        val denRe = 1.0 + c.a1 * cosw + c.a2 * cos2w
        val denIm = -c.a1 * sinw - c.a2 * sin2w

        val numMagSq = numRe * numRe + numIm * numIm
        val denMagSq = denRe * denRe + denIm * denIm
        val magSq = if (denMagSq > 1e-12) numMagSq / denMagSq else 0.0

        return 10.0 * log10(magSq.coerceAtLeast(1e-12))
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

    // Red de seguridad de ultimo recurso: con el headroom exacto de
    // computeHeadroomLinear casi nunca deberia entrar en accion. Cubre
    // picos puntuales por muestra (el calculo de headroom es en
    // frecuencia, no exhaustivo por cada muestra real) y la ganancia
    // extra que pueda sumar el preamp manual, que corre antes que este
    // procesador en la cadena. En vez de recortar seco (que suena "feo"),
    // si una muestra se acerca al limite de 16 bits la comprime suave con
    // una curva tanh; por debajo del umbral no toca nada.
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