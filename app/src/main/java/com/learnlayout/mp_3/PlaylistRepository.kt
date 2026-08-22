package com.learnlayout.mp_3

import android.content.Context
import java.util.UUID

object PlaylistRepository {

    const val FAVORITES_PLAYLIST_ID = "favorites_default"
    private const val FAVORITES_PLAYLIST_NAME = "Favoritos"

    private fun dao(context: Context): PlaylistDao {
        LegacyDataMigrator.migrateIfNeeded(context)
        return AppDatabase.getInstance(context).playlistDao()
    }

    fun getAllPlaylists(context: Context): MutableList<Playlist> {
        val dao = dao(context)
        return dao.getAllPlaylistEntities().map { entity ->
            Playlist(
                id = entity.id,
                name = entity.name,
                songIds = dao.getSongIdsForPlaylist(entity.id).toMutableList(),
                coverImageUri = entity.coverImageUri
            )
        }.toMutableList()
    }

    /**
     * Crea la playlist "Favoritos" si todavia no existe. Se debe llamar una vez
     * al arrancar la pantalla principal para garantizar que siempre este disponible.
     */
    fun ensureFavoritesPlaylist(context: Context) {
        val dao = dao(context)
        if (dao.getPlaylistEntity(FAVORITES_PLAYLIST_ID) == null) {
            dao.insertPlaylist(
                PlaylistEntity(
                    id = FAVORITES_PLAYLIST_ID,
                    name = FAVORITES_PLAYLIST_NAME,
                    coverImageUri = null,
                    sortOrder = -1 // Favoritos siempre primero, igual que antes
                )
            )
        }
    }

    fun isFavorite(context: Context, songId: Long): Boolean {
        return dao(context).getSongIdsForPlaylist(FAVORITES_PLAYLIST_ID).contains(songId)
    }

    /**
     * Agrega o quita la cancion de "Favoritos". Devuelve true si quedo marcada
     * como favorita, false si se quito.
     */
    fun toggleFavorite(context: Context, songId: Long): Boolean {
        val dao = dao(context)
        ensureFavoritesPlaylist(context)

        val currentSongIds = dao.getSongIdsForPlaylist(FAVORITES_PLAYLIST_ID)
        return if (currentSongIds.contains(songId)) {
            dao.removeSongFromPlaylist(FAVORITES_PLAYLIST_ID, songId)
            false
        } else {
            val nextPosition = dao.getMaxPosition(FAVORITES_PLAYLIST_ID) + 1
            dao.insertPlaylistSong(PlaylistSongCrossRef(FAVORITES_PLAYLIST_ID, songId, nextPosition))
            true
        }
    }

    fun createPlaylist(context: Context, name: String): Playlist {
        val dao = dao(context)
        val id = UUID.randomUUID().toString()
        val nextOrder = dao.getMaxSortOrder() + 1
        dao.insertPlaylist(PlaylistEntity(id = id, name = name, coverImageUri = null, sortOrder = nextOrder))
        return Playlist(id, name, mutableListOf())
    }

    fun deletePlaylist(context: Context, playlistId: String) {
        if (playlistId == FAVORITES_PLAYLIST_ID) return
        dao(context).deletePlaylist(playlistId)
    }

    fun addSongToPlaylist(context: Context, playlistId: String, songId: Long) {
        val dao = dao(context)
        val currentSongIds = dao.getSongIdsForPlaylist(playlistId)
        if (currentSongIds.contains(songId)) return
        val nextPosition = dao.getMaxPosition(playlistId) + 1
        dao.insertPlaylistSong(PlaylistSongCrossRef(playlistId, songId, nextPosition))
    }

    fun removeSongFromPlaylist(context: Context, playlistId: String, songId: Long) {
        dao(context).removeSongFromPlaylist(playlistId, songId)
    }

    /**
     * Quita [songId] de TODAS las playlists (incluida Favoritos). Se usa
     * cuando la cancion se borra del dispositivo, para que no queden
     * referencias colgando a un archivo que ya no existe.
     */
    fun removeSongFromAllPlaylists(context: Context, songId: Long) {
        dao(context).removeSongFromAllPlaylists(songId)
    }

    fun getPlaylistById(context: Context, playlistId: String): Playlist? {
        val dao = dao(context)
        val entity = dao.getPlaylistEntity(playlistId) ?: return null
        return Playlist(
            id = entity.id,
            name = entity.name,
            songIds = dao.getSongIdsForPlaylist(entity.id).toMutableList(),
            coverImageUri = entity.coverImageUri
        )
    }

    fun setCoverImage(context: Context, playlistId: String, uri: String) {
        val dao = dao(context)
        val entity = dao.getPlaylistEntity(playlistId) ?: return
        dao.updatePlaylist(entity.copy(coverImageUri = uri))
    }

    /**
     * Guarda el nuevo orden de [orderedSongIds] dentro de la playlist,
     * despues de que el usuario arrastra una cancion en
     * PlaylistDetailActivity. insertPlaylistSong ya usa REPLACE ante
     * conflicto de la llave primaria (playlistId, songId), asi que
     * reinsertar con la posicion nueva sobreescribe la vieja sin duplicar
     * filas.
     */
    fun reorderPlaylistSongs(context: Context, playlistId: String, orderedSongIds: List<Long>) {
        val dao = dao(context)
        val refs = orderedSongIds.mapIndexed { index, songId ->
            PlaylistSongCrossRef(playlistId, songId, index)
        }
        dao.insertPlaylistSongs(refs)
    }
}