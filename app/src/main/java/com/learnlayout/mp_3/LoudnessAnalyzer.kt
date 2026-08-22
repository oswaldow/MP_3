package com.learnlayout.mp_3

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Analiza el volumen promedio de un archivo de audio para que
 * SongGainRepository pueda calcular cuanta ganancia aplicarle despues, de
 * forma que suene parejo con el resto de la biblioteca.
 *
 * IMPORTANTE: esto NO es un analizador de loudness real (no implementa el
 * estandar EBU R128 / ReplayGain 2.0, que requiere filtros de ponderacion
 * psicoacustica). Es una aproximacion practica: decodifica el audio a PCM
 * y mide el RMS (root-mean-square) en dBFS de los primeros segundos de la
 * cancion, suficiente para saber si suena "fuerte" o "bajito" en promedio.
 */
object LoudnessAnalyzer {

    private const val TAG = "MP3_LOUDNESS"

    // No hace falta decodificar la cancion completa para tener una medida
    // representativa: con este tope alcanza y el analisis se mantiene
    // rapido incluso en canciones largas.
    private const val MAX_ANALYSIS_MS = 45_000L
    private const val TIMEOUT_US = 10_000L

    /** Devuelve el RMS en dBFS (tipicamente entre -40 y 0), o null si no se pudo analizar. */
    fun analyzeRmsDb(context: Context, uri: Uri): Double? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                Log.w(TAG, "analyzeRmsDb: sin pista de audio en $uri")
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sumSquares = 0.0
            var sampleCount = 0L
            var sawInputEnd = false
            var sawOutputEnd = false
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                while (!sawOutputEnd && bufferInfo.presentationTimeUs < MAX_ANALYSIS_MS * 1000L) {
                    if (!sawInputEnd) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEnd = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.order(ByteOrder.nativeOrder())
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            // PCM 16-bit con signo: se toman las muestras en
                            // pares de bytes, sin distinguir canal, para un
                            // RMS global de la señal.
                            while (outputBuffer.remaining() >= 2) {
                                val sample = outputBuffer.short.toDouble()
                                sumSquares += sample * sample
                                sampleCount++
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEnd = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            if (sampleCount == 0L) {
                Log.w(TAG, "analyzeRmsDb: no se decodifico ninguna muestra de $uri")
                return null
            }

            val rms = sqrt(sumSquares / sampleCount)
            if (rms <= 0.0) return null

            // dBFS relativo a la amplitud maxima de PCM 16-bit (32768).
            return 20.0 * log10(rms / 32768.0)

        } catch (e: Exception) {
            Log.e(TAG, "analyzeRmsDb: fallo analizando $uri", e)
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }
}