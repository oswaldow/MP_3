package com.learnlayout.mp_3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Aplica una ganancia lineal por cancion para "normalizar" el volumen
 * entre canciones de fuentes distintas (ver SongGainRepository /
 * LoudnessAnalyzer), Y el preamp manual del ecualizador (ver
 * EqualizerRepository). Guarda su estado en un companion object: hay una
 * sola cadena de audio compartida por toda la app, y la ganancia
 * "vigente" es la de la cancion que esta sonando ahora mismo.
 * PlaybackEngine actualiza setCurrentGainDb() cada vez que arranca una
 * cancion nueva.
 *
 * El preamp vive aqui (no en el Equalizer nativo de
 * android.media.audiofx) porque ese Equalizer solo controla bandas -no
 * tiene concepto de ganancia maestra-, asi que EqualizerRepository
 * reutiliza esta etapa, que ya multiplica una ganancia lineal sobre el
 * PCM antes de que llegue al AudioTrack.
 */
@UnstableApi
class ReplayGainAudioProcessor : AudioProcessor {

    companion object {
        @Volatile private var enabled = false
        @Volatile private var currentGainLinear = 1.0
        @Volatile private var userGainLinear = 1.0
        @Volatile private var preampLinear = 1.0

        fun setEnabled(value: Boolean) {
            enabled = value
        }

        fun isEnabled(): Boolean = enabled

        /** [gainDb] ya viene calculado (y acotado) por SongGainRepository. */
        fun setCurrentGainDb(gainDb: Double) {
            currentGainLinear = 10.0.pow(gainDb / 20.0)
        }

        /**
         * Ganancia extra manual (milibeles) que el usuario controla con el
         * slider de "Normalizar volumen" en Ajustes (ver
         * SettingsRepository.getVolumeNormalizationGainMillibel()). Se
         * aplica sobre el resultado ya normalizado de cada cancion, y solo
         * suena si [enabled] es true (ver queueInput), igual que
         * currentGainLinear.
         */
        fun setUserGainMillibel(userGainMillibel: Int) {
            userGainLinear = 10.0.pow(userGainMillibel / 2000.0)
        }

        /**
         * Ganancia extra manual del ecualizador, en milibeles (100 mB = 1
         * dB). La fija EqualizerRepository: 0 si el ecualizador esta
         * desactivado o no disponible en el dispositivo, o el valor
         * guardado por el usuario si esta activo.
         */
        fun setPreampMillibel(preampMillibel: Int) {
            preampLinear = 10.0.pow(preampMillibel / 2000.0)
        }
    }

    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        return outputAudioFormat
    }

    override fun isActive(): Boolean = outputAudioFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val out = replaceOutputBuffer(size)

        val replayGain = if (enabled) currentGainLinear * userGainLinear else 1.0
        val totalGain = replayGain * preampLinear

        if (totalGain == 1.0) {
            // Bypass: copia directa, sin gastar CPU si no hay ninguna
            // ganancia que aplicar (ni ReplayGain ni preamp).
            out.put(inputBuffer)
        } else {
            val src = inputBuffer.order(ByteOrder.nativeOrder())
            while (src.remaining() >= 2) {
                val sample = src.short.toDouble() * totalGain
                out.putShort(softLimit(sample).toInt().toShort())
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

    // Mismo limitador suave (curva tanh) que antes: si la suma de
    // ReplayGain + preamp acerca un pico al limite de 16 bits, se
    // comprime en vez de recortarse seco (distorsion).
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
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}