package com.learnlayout.mp_3

import android.content.Context
import android.provider.MediaStore
import android.util.Log

/**
 * Cuando MediaStore le asigna un _ID nuevo a un archivo de audio que ya
 * estaba guardado en la app bajo su _ID viejo (esto pasa, sobre todo en
 * MIUI/HyperOS, cuando SongFileTagWriter reescribe el archivo completo al
 * incrustar caratula/letra y dispara un rescan), todas las tablas que
 * guardan ese songId como referencia quedan "huerfanas": apuntan a un _ID
 * que ya no existe, aunque el archivo siga ahi con otro _ID.
 *
 * Esto es lo que causaba que playlists, favoritos y "mas escuchadas"
 * mostraran menos canciones de las que en realidad tienen guardadas (ver
 * el log de diagnostico en PlaylistDetailActivity que confirmo esto: el
 * 100% de los songId "faltantes" no existen en MediaStore bajo ningun
 * filtro).
 *
 * Este objeto centraliza dos cosas:
 *  - remapSongId(): se llama en el momento exacto en que se detecta el
 *    cambio de _ID (ver SongFileTagWriter.writeToFile()), y actualiza
 *    todas las tablas para que sigan apuntando a la cancion correcta.
 *    Esto evita que el problema vuelva a ocurrir de aqui en adelante.
 *  - pruneOrphanedReferences(): limpieza para las referencias que ya se
 *    perdieron ANTES de que existiera este mecanismo. No puede recuperar
 *    a que cancion actual correspondia cada songId perdido (esa
 *    informacion ya no existe), asi que solo borra la referencia rota en
 *    vez de dejar que la playlist seguir "contando" una cancion fantasma.
 *    El usuario tiene que volver a agregar esas canciones a mano una
 *    sola vez.
 */
object SongIdMigrator {

    private const val TAG = "MP3_SongIdMigrator"

    /**
     * Reasigna toda referencia a [oldId] para que apunte a [newId] en
     * playlists (incluida Favoritos), contadores de reproduccion,
     * ganancia (ReplayGain) y letra guardada offline.
     *
     * Debe llamarse desde un hilo de fondo (hace varias operaciones de
     * Room + SharedPreferences). Cada tabla se actualiza en su propio
     * try/catch: un choque de clave primaria en una no debe impedir que
     * las demas se actualicen igual.
     */
    fun remapSongId(context: Context, oldId: Long, newId: Long) {
        if (oldId == newId) return

        val db = AppDatabase.getInstance(context)

        try {
            db.playlistDao().remapSongId(oldId, newId)
        } catch (e: Exception) {
            Log.w(TAG, "remapSongId(): fallo al remapear en playlist_songs ($oldId -> $newId): ${e.message}")
        }

        try {
            db.playCountDao().remapSongId(oldId, newId)
        } catch (e: Exception) {
            Log.w(TAG, "remapSongId(): fallo al remapear en play_counts ($oldId -> $newId): ${e.message}")
        }

        try {
            db.songGainDao().remapSongId(oldId, newId)
        } catch (e: Exception) {
            Log.w(TAG, "remapSongId(): fallo al remapear en song_gains ($oldId -> $newId): ${e.message}")
        }

        try {
            SavedLyricsRepository.renameKey(context, oldId, newId)
        } catch (e: Exception) {
            Log.w(TAG, "remapSongId(): fallo al remapear la letra guardada ($oldId -> $newId): ${e.message}")
        }

        Log.i(TAG, "remapSongId(): $oldId -> $newId aplicado en playlists, contadores, ganancia y letra guardada")
    }

    /**
     * Elimina, de playlists, contadores de reproduccion y ganancia
     * guardada, cualquier songId que ya no exista en MediaStore bajo
     * NINGUN filtro (ni siquiera el de SongRepository.getAllSongs()) —
     * es decir, canciones que de verdad ya no se pueden recuperar por
     * ese _ID, no canciones simplemente ocultas por el filtro normal de
     * la app.
     *
     * Devuelve cuantas referencias huerfanas se borraron. Debe llamarse
     * desde un hilo de fondo.
     */
    fun pruneOrphanedReferences(context: Context): Int {
        val db = AppDatabase.getInstance(context)
        val existingIds = queryAllMediaStoreIdsUnfiltered(context)
        var removed = 0

        val playlistIds = db.playlistDao().getAllPlaylistEntities().map { it.id }
        for (playlistId in playlistIds) {
            val songIds = db.playlistDao().getSongIdsForPlaylist(playlistId)
            for (songId in songIds) {
                if (songId !in existingIds) {
                    db.playlistDao().removeSongFromPlaylist(playlistId, songId)
                    removed++
                }
            }
        }

        val playCountIds = db.playCountDao().getAllEntities().map { it.songId }
        for (songId in playCountIds) {
            if (songId !in existingIds) {
                db.playCountDao().deleteEntity(songId)
                removed++
            }
        }

        val gainIds = db.songGainDao().getAllSongIds()
        for (songId in gainIds) {
            if (songId !in existingIds) {
                db.songGainDao().deleteGain(songId)
                removed++
            }
        }

        Log.i(TAG, "pruneOrphanedReferences(): $removed referencias huerfanas eliminadas")
        return removed
    }

    /**
     * Todos los _ID que existen HOY en MediaStore, sin aplicar el filtro
     * de duracion/ruta que usa SongRepository.getAllSongs(). Se usa solo
     * para decidir que es "de verdad no existe" (candidato a pruneOrphan)
     * en vez de "existe pero el filtro normal de la app lo esconde".
     */
    private fun queryAllMediaStoreIdsUnfiltered(context: Context): Set<Long> {
        val ids = mutableSetOf<Long>()
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(idColumn))
            }
        }
        return ids
    }
}