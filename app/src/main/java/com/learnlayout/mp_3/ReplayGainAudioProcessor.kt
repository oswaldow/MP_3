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
 * LoudnessAnalyzer). Igual que SoftwareEqualizerProcessor, guarda su
 * estado en un companion object: hay una sola cadena de audio compartida
 * por toda la app, y la ganancia "vigente" es la de la cancion que esta
 * sonando ahora mismo. PlaybackEngine actualiza setCurrentGainDb() cada
 * vez que arranca una cancion nueva.
 */
@UnstableApi
class ReplayGainAudioProcessor : AudioProcessor {

    companion object {
        @Volatile private var enabled = false
        @Volatile private var currentGainLinear = 1.0

        fun setEnabled(value: Boolean) {
            enabled = value
        }

        fun isEnabled(): Boolean = enabled

        /** [gainDb] ya viene calculado (y acotado) por SongGainRepository. */
        fun setCurrentGainDb(gainDb: Double) {
            currentGainLinear = 10.0.pow(gainDb / 20.0)
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

        if (!enabled || currentGainLinear == 1.0) {
            // Bypass: copia directa, sin gastar CPU si esta desactivado o
            // la ganancia actual es neutra.
            out.put(inputBuffer)
        } else {
            val src = inputBuffer.order(ByteOrder.nativeOrder())
            val gain = currentGainLinear
            while (src.remaining() >= 2) {
                val sample = src.short.toDouble() * gain
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

    // Mismo limitador suave (curva tanh) que SoftwareEqualizerProcessor: si
    // una cancion bajita se amplifica y algun pico se acerca al limite de
    // 16 bits, se comprime en vez de recortarse seco (distorsion).
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