
package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Lee la caratula y la letra que YA vienen guardadas dentro del propio
 * archivo de audio (ID3v2 APIC/USLT en MP3, atomos covr/(c)lyr en M4A/AAC,
 * bloque de imagen + Vorbis Comment en FLAC/OGG), usando la misma libreria
 * (jaudiotagger-android) que SongFileTagWriter ya usa para escribir tags.
 *
 * Se usa para darle prioridad a estos datos "de fabrica" del archivo antes
 * de salir a buscar algo por red: si una cancion ya trae letra sincronizada
 * y caratula puestas por quien la etiqueto, no tiene sentido ignorarlas y
 * forzar al usuario a buscarlas de nuevo (ver AlbumArtRepository
 * .loadCoverCacheOnly, LyricsPanelController.loadForSong y
 * LyricsArtStatusRepository.computeStatus).
 *
 * A diferencia de SongFileTagWriter, esto NO requiere el permiso de
 * "Todos los archivos" (MANAGE_EXTERNAL_STORAGE): leer metadatos con
 * jaudiotagger no necesita mas permiso que el que la app ya tiene para
 * reproducir el archivo (via su ruta real en MediaStore).
 *
 * Todo lo publico de este objeto hace I/O de disco (abre y parsea el
 * archivo completo con jaudiotagger) y debe llamarse SIEMPRE desde un hilo
 * de fondo (ver AppExecutors.runInBackground), nunca desde el hilo
 * principal.
 */
object EmbeddedMetadataReader {

    private const val TAG = "EmbeddedMetadataReader"

    // Mismo limite que AlbumArtRepository, para no decodificar bitmaps
    // gigantes de caratulas embebidas en alta resolucion.
    private const val TARGET_MAX_DIMENSION_PX = 480

    data class Embedded(
        val artwork: Bitmap?,
        val lyrics: LyricsResult?
    )

    /**
     * Lee caratula y letra embebidas en una sola pasada. Abrir el archivo
     * con jaudiotagger es lo costoso, asi que conviene sacar ambos datos
     * de la misma lectura en vez de abrir el archivo dos veces (ver
     * LyricsArtStatusRepository.computeStatus, que necesita ambos a la vez
     * al escanear toda la biblioteca).
     */
    fun read(context: Context, song: Song): Embedded {
        val path = resolveFilePath(context, song)
        if (path.isNullOrBlank()) return Embedded(null, null)

        val file = File(path)
        if (!file.exists()) return Embedded(null, null)

        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: return Embedded(null, null)

            val artwork = try {
                tag.firstArtwork?.binaryData?.let { bytes -> decodeSampledBitmapFromBytes(bytes) }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo leer la caratula embebida de $path: ${e.message}")
                null
            }

            val lyrics = try {
                val raw = tag.getFirst(FieldKey.LYRICS)
                if (raw.isNullOrBlank()) null else LyricsRepository.parseEmbeddedText(raw)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo leer la letra embebida de $path: ${e.message}")
                null
            }

            Embedded(artwork, lyrics)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer los tags embebidos de $path: ${e.message}")
            Embedded(null, null)
        }
    }

    /** Solo la caratula embebida (para cuando no hace falta la letra). */
    fun readArtwork(context: Context, song: Song): Bitmap? = read(context, song).artwork

    /** Solo la letra embebida (para cuando no hace falta la caratula). */
    fun readLyrics(context: Context, song: Song): LyricsResult? = read(context, song).lyrics

    @Suppress("DEPRECATION")
    private fun resolveFilePath(context: Context, song: Song): String? {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        context.contentResolver.query(song.uri, projection, null, null, null)?.use { cursor ->
            val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            if (dataColumn >= 0 && cursor.moveToFirst()) {
                return cursor.getString(dataColumn)
            }
        }
        return null
    }

    private fun decodeSampledBitmapFromBytes(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var inSampleSize = 1
        if (height > TARGET_MAX_DIMENSION_PX || width > TARGET_MAX_DIMENSION_PX) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= TARGET_MAX_DIMENSION_PX &&
                (halfWidth / inSampleSize) >= TARGET_MAX_DIMENSION_PX
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}