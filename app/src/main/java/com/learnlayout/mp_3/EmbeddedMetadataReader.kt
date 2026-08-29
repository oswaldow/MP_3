package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotReadVideoException
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.RandomAccessFile

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
        val label = "id=${song.id} title=\"${song.title}\" artist=\"${song.artist}\" uri=${song.uri}"
        val path = resolveFilePath(context, song)
        Log.d(TAG, "read $label -> resolvedPath=$path")
        if (path.isNullOrBlank()) return Embedded(null, null)

        val file = File(path)
        if (!file.exists()) {
            Log.d(TAG, "read $label -> file NO EXISTE en $path")
            return Embedded(null, null)
        }

        val audioFile = try {
            AudioFileIO.read(file)
        } catch (e: CannotReadVideoException) {
            // jaudiotagger clasifica como "video" cualquier mp4/m4a cuyo
            // PRIMER track tenga un handler "vide" en vez de "soun" -y
            // rechaza leer el archivo entero, aunque mas adelante exista
            // una pista de audio real. Esto pasa con algunos m4a que bajo
            // Muka descarga (quedan remuxados con una pista de video -a
            // veces solo la miniatura- delante de la de audio). jaudiotagger
            // no tiene forma de saltear ese track, asi que para no perder
            // la caratula en estos casos la leemos a mano, parseando
            // directamente los atomos MP4 (moov/udta/meta/ilst/covr/data)
            // sin pasar por la validacion "es un archivo de audio" de
            // jaudiotagger. La letra (ID3/tag completo) sigue sin poder
            // leerse en este caso: para eso si hace falta un parser de tag
            // completo, que es justamente lo que jaudiotagger rechaza dar.
            Log.w(TAG, "read $label -> jaudiotagger dice Mp4Video, leyendo covr a mano: ${e.message}")
            val manualBytes = try {
                readMp4CoverArtManually(file)
            } catch (e2: Exception) {
                Log.w(TAG, "read $label -> fallo el parseo manual de atomos MP4: ${e2.message}")
                null
            }
            val manualArtwork = manualBytes?.let { decodeSampledBitmapFromBytes(it) }
            Log.d(TAG, "read $label path=$path -> artworkFound(manual)=${manualArtwork != null}")
            return Embedded(manualArtwork, null)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer los tags embebidos de $path: ${e.message}")
            return Embedded(null, null)
        }

        return try {
            val tag = audioFile.tag ?: return Embedded(null, null)

            val artwork = try {
                tag.firstArtwork?.binaryData?.let { bytes -> decodeSampledBitmapFromBytes(bytes) }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo leer la caratula embebida de $path: ${e.message}")
                null
            }
            Log.d(TAG, "read $label path=$path -> artworkFound=${artwork != null}")

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

    // ---------- Lector manual de atomos MP4 (fallback para archivos que
    // jaudiotagger rechaza por creerlos "video") ----------
    //
    // Recorre a mano la estructura de cajas (boxes) de un contenedor
    // MP4/M4A: cada caja es [4 bytes tamano][4 bytes tipo ASCII][contenido].
    // Baja recursivamente por moov -> udta -> meta (los primeros 4 bytes de
    // "meta" son version+flags, no contenido) -> ilst -> covr, y de ahi
    // extrae el sub-atomo "data" (que trae 8 bytes de cabecera propios:
    // tipo de dato + reservado) con los bytes crudos de la imagen. No
    // valida en ningun momento si el archivo "es de audio": simplemente
    // busca el primer covr/data que encuentre, que es exactamente lo que
    // jaudiotagger hace tambien puertas adentro, solo que sin el chequeo
    // previo de video que nos esta bloqueando.

    private fun readMp4CoverArtManually(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            return findCoverArtBytes(raf, 0L, raf.length())
        }
    }

    private fun findCoverArtBytes(raf: RandomAccessFile, start: Long, end: Long): ByteArray? {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val header = ByteArray(8)
            raf.readFully(header)

            var boxSize = readUInt32BE(header, 0)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var headerSize = 8L

            if (boxSize == 1L) {
                // Tamano extendido de 64 bits (caja "size64", poco comun
                // pero valido segun el estandar ISO BMFF).
                if (pos + 16 > end) break
                val ext = ByteArray(8)
                raf.seek(pos + 8)
                raf.readFully(ext)
                boxSize = readUInt64BE(ext, 0)
                headerSize = 16L
            } else if (boxSize == 0L) {
                // Tamano 0 = la caja ocupa hasta el final del archivo/padre.
                boxSize = end - pos
            }

            if (boxSize < headerSize || pos + boxSize > end) {
                // Caja corrupta o truncada: no seguimos parseando esta rama.
                break
            }

            val contentStart = pos + headerSize
            val contentEnd = pos + boxSize

            when (type) {
                "moov", "udta", "ilst" -> {
                    val found = findCoverArtBytes(raf, contentStart, contentEnd)
                    if (found != null) return found
                }
                "meta" -> {
                    // "meta" es una FullBox: trae 4 bytes de version+flags
                    // antes de sus hijos.
                    if (contentStart + 4 <= contentEnd) {
                        val found = findCoverArtBytes(raf, contentStart + 4, contentEnd)
                        if (found != null) return found
                    }
                }
                "covr" -> {
                    val data = readDataAtomPayload(raf, contentStart, contentEnd)
                    if (data != null) return data
                }
            }

            pos += boxSize
        }
        return null
    }

    /**
     * Lee el contenido de un sub-atomo "data" (usado por iTunes dentro de
     * covr y de cualquier otro campo de metadata): [4 bytes tamano][4
     * bytes tipo="data"][4 bytes tipo de dato][4 bytes reservado][bytes
     * crudos].
     */
    private fun readDataAtomPayload(raf: RandomAccessFile, start: Long, end: Long): ByteArray? {
        if (start + 16 > end) return null
        raf.seek(start)
        val header = ByteArray(8)
        raf.readFully(header)
        val boxSize = readUInt32BE(header, 0)
        val type = String(header, 4, 4, Charsets.US_ASCII)
        if (type != "data") return null
        if (boxSize < 16L || start + boxSize > end) return null

        val payloadStart = start + 16 // 8 (header) + 4 (tipo de dato) + 4 (reservado)
        val payloadEnd = start + boxSize
        val length = (payloadEnd - payloadStart).toInt()
        if (length <= 0) return null

        val out = ByteArray(length)
        raf.seek(payloadStart)
        raf.readFully(out)
        return out
    }

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun readUInt64BE(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
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