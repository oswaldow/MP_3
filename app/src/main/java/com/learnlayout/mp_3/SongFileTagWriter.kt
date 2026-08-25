package com.learnlayout.mp_3

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Escribe la caratula y la letra directamente en el archivo de audio de la
 * cancion (a diferencia de AlbumArtRepository/SavedLyricsRepository, que
 * guardan en el almacenamiento privado de la app y se pierden al
 * desinstalar). Usa jaudiotagger-android, que abstrae el formato de tag
 * segun la extension del archivo:
 *   - MP3   -> ID3v2 (APIC para caratula, USLT para letra)
 *   - M4A/AAC -> atomos MP4 (covr, ©lyr)
 *   - FLAC/OGG -> Vorbis Comment + bloque de imagen
 *
 * Requiere acceso a "Todos los archivos" (MANAGE_EXTERNAL_STORAGE) en
 * Android 11+, porque estos archivos no los creo la app: estan en el
 * almacenamiento compartido del telefono, fuera del sandbox normal de
 * Android. En Android 9 y anteriores no hace falta nada extra; en Android
 * 10 se cubre con requestLegacyExternalStorage en el manifest.
 *
 * Todo lo publico de este objeto que toca disco (writeToFile) debe
 * llamarse desde un hilo de fondo (ver AppExecutors.runInBackground):
 * jaudiotagger hace I/O sincrono y puede reescribir el archivo completo.
 */
object SongFileTagWriter {

    private const val TAG = "MP3_TagWriter"

    /** true si la app ya puede escribir libremente en el almacenamiento compartido. */
    fun hasManageStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Manda al usuario a la pantalla del sistema donde puede activar
     * "Acceso a todos los archivos" para esta app. No hace nada en
     * Android 10 o anterior (ahi no existe ese permiso).
     */
    fun requestManageStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir la pantalla especifica de la app, probando la general: ${e.message}")
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                Log.e(TAG, "No se pudo abrir ninguna pantalla de permiso de almacenamiento: ${e2.message}", e2)
            }
        }
    }

    /**
     * Escribe [coverBitmap], [lyricsResult] y/o [title]/[artist] en el
     * archivo de audio real de [song]. Cualquiera puede venir null para
     * dejar ese dato tal cual esta. Devuelve true si logro escribir al
     * menos uno en disco.
     *
     * [title]/[artist] en blanco se ignoran (no se sobreescribe el tag
     * con un valor vacio).
     *
     * Debe llamarse desde un hilo de fondo.
     */
    fun writeToFile(
        context: Context,
        song: Song,
        coverBitmap: Bitmap? = null,
        lyricsResult: LyricsResult? = null,
        title: String? = null,
        artist: String? = null
    ): Boolean {
        if (coverBitmap == null && lyricsResult == null && title.isNullOrBlank() && artist.isNullOrBlank()) {
            return false
        }

        val path = resolveFilePath(context, song)
        if (path.isNullOrBlank()) {
            Log.w(TAG, "writeToFile(): no se pudo resolver la ruta real del archivo (songId=${song.id})")
            return false
        }

        val file = File(path)
        if (!file.exists() || !file.canWrite()) {
            Log.w(TAG, "writeToFile(): archivo no existe o no se puede escribir: $path (¿falta el permiso de Todos los archivos?)")
            return false
        }

        // Tanto reescribir el archivo (jaudiotagger reescribe el archivo
        // COMPLETO) como el reescaneo posterior de MediaScannerConnection
        // hacen que el sistema pise DATE_ADDED y DATE_MODIFIED con "ahora".
        // La pantalla de "Agregadas recientemente" ordena por el maximo
        // entre esas dos columnas (ver SongRepository.getAllSongs), asi
        // que si solo restauramos DATE_ADDED (como antes) la cancion igual
        // salta al primer lugar por culpa de DATE_MODIFIED. Guardamos
        // ambos valores ORIGINALES aqui:
        //  - los que ya tenia en MediaStore, para reponerlos ahi despues
        //    del rescan.
        //  - el lastModified() real del archivo en disco, para reponerlo
        //    tambien a nivel sistema de archivos: si no, un reescaneo
        //    completo futuro (por ejemplo tras reiniciar el telefono)
        //    volveria a leer el mtime nuevo del archivo y el problema
        //    reaparece igual.
        val originalDateAdded = resolveDateAdded(context, song.uri)
        val originalDateModified = resolveDateModified(context, song.uri)
        val originalFileLastModified = file.lastModified()

        var tempArtFile: File? = null
        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault

            if (coverBitmap != null) {
                val imageBytes = ByteArrayOutputStream().use { stream ->
                    coverBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    stream.toByteArray()
                }

                if (tag is FlacTag) {
                    // El fork de jaudiotagger para Android quito el codigo
                    // que "primaba" el artwork (Artwork.setImageFromData(),
                    // que usaba javax.imageio para sacar ancho/alto/etc, y
                    // que en este fork simplemente lanza
                    // UnsupportedOperationException siempre). El bloque de
                    // imagen de FLAC SI necesita ancho/alto guardados, asi
                    // que se los damos a mano con el propio Bitmap (que ya
                    // los trae) en vez de dejar que la libreria intente
                    // calcularlos sola.
                    val artworkField = tag.createArtworkField(
                        imageBytes,
                        PictureTypes.DEFAULT_ID,
                        "image/jpeg",
                        "",
                        coverBitmap.width,
                        coverBitmap.height,
                        24,
                        0
                    )
                    tag.deleteArtworkField()
                    tag.setField(artworkField)
                } else {
                    // MP3 (ID3v2 APIC) y M4A/AAC (atomo covr) no guardan
                    // ancho/alto dentro del tag, asi que aqui si funciona
                    // el camino normal de la libreria.
                    tempArtFile = File(context.cacheDir, "tag_art_${song.id}.jpg")
                    FileOutputStream(tempArtFile).use { out -> out.write(imageBytes) }
                    val artwork = ArtworkFactory.createArtworkFromFile(tempArtFile)
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                }
            }

            if (lyricsResult != null) {
                val text = toLrcOrPlainText(lyricsResult)
                if (text.isNotBlank()) {
                    tag.setField(FieldKey.LYRICS, text)
                }
            }

            if (!title.isNullOrBlank()) {
                tag.setField(FieldKey.TITLE, title)
            }

            if (!artist.isNullOrBlank()) {
                tag.setField(FieldKey.ARTIST, artist)
            }

            AudioFileIO.write(audioFile)

            // Repone el mtime real del archivo en disco ANTES del rescan,
            // para que cuando MediaScannerConnection lea el archivo ya
            // encuentre la fecha vieja y no tenga que "corregirse" despues.
            if (originalFileLastModified > 0L) {
                file.setLastModified(originalFileLastModified)
            }

            // Sin esto, el sistema (y otras apps que lean MediaStore) pueden
            // seguir viendo los metadatos viejos hasta el proximo escaneo
            // completo del almacenamiento. El callback espera a que el
            // rescan termine (y a que MediaStore ya haya pisado DATE_ADDED
            // y DATE_MODIFIED con "ahora") para recien ahi devolverlos a su
            // valor original.
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(path),
                null
            ) { _, scannedUri ->
                val finalUri = scannedUri ?: song.uri
                restoreDate(context, finalUri, MediaStore.Audio.Media.DATE_ADDED, originalDateAdded)
                restoreDate(context, finalUri, MediaStore.Audio.Media.DATE_MODIFIED, originalDateModified)
            }

            Log.d(TAG, "writeToFile(): OK songId=${song.id} archivo=$path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeToFile(): fallo al escribir tags en $path: ${e.message}", e)
            false
        } finally {
            tempArtFile?.delete()
        }
    }

    /**
     * Si hay letra sincronizada la guarda en formato LRC estandar
     * ([mm:ss.xx]texto por linea), que la mayoria de reproductores externos
     * puede mostrar como letra simple, y que esta misma app puede volver a
     * parsear como sincronizada si en el futuro se le agrega ese soporte
     * de lectura. Sin lineas sincronizadas, cae a texto plano.
     */
    private fun toLrcOrPlainText(result: LyricsResult): String {
        val lines = result.syncedLines
        if (!lines.isNullOrEmpty()) {
            return lines.joinToString("\n") { line -> "${formatLrcTimestamp(line.timeMs)}${line.text}" }
        }
        return result.plainLyrics.orEmpty()
    }

    private fun formatLrcTimestamp(timeMs: Long): String {
        val totalCentiseconds = timeMs / 10
        val minutes = totalCentiseconds / 6000
        val seconds = (totalCentiseconds / 100) % 60
        val centiseconds = totalCentiseconds % 100
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centiseconds)
    }

    /**
     * MediaStore.Audio.Media.DATA esta deprecado desde API 29, pero se
     * sigue llenando y consultando funciona sin problema; lo que cambio en
     * API 29+ es que ya no se puede usar directo para *abrir* el archivo
     * sin permiso adicional (de ahi MANAGE_EXTERNAL_STORAGE). Aqui solo se
     * usa para saber en que ruta del disco esta.
     */
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

    /** Lee el DATE_ADDED actual de la cancion en MediaStore, antes de tocar el archivo. */
    private fun resolveDateAdded(context: Context, uri: Uri): Long? {
        return resolveDateColumn(context, uri, MediaStore.Audio.Media.DATE_ADDED)
    }

    /** Lee el DATE_MODIFIED actual de la cancion en MediaStore, antes de tocar el archivo. */
    private fun resolveDateModified(context: Context, uri: Uri): Long? {
        return resolveDateColumn(context, uri, MediaStore.Audio.Media.DATE_MODIFIED)
    }

    private fun resolveDateColumn(context: Context, uri: Uri, column: String): Long? {
        val projection = arrayOf(column)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndex(column)
            if (columnIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getLong(columnIndex)
            }
        }
        return null
    }

    /**
     * Repone [column] (DATE_ADDED o DATE_MODIFIED) al valor que tenia antes
     * del rescan. Se llama desde el callback de MediaScannerConnection.scanFile,
     * es decir, ya despues de que MediaStore termino de reindexar el
     * archivo (y de pisar ambas columnas con la fecha del rescan).
     */
    private fun restoreDate(context: Context, uri: Uri, column: String, originalValue: Long?) {
        if (originalValue == null || originalValue <= 0L) return
        try {
            val values = ContentValues()
            values.put(column, originalValue)
            context.applicationContext.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo restaurar $column tras el rescan: ${e.message}")
        }
    }
}