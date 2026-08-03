package com.learnlayout.mp_3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object PlaylistRepository {

    private const val PREFS_NAME = "playlists_prefs"
    private const val KEY_PLAYLISTS = "playlists_json"

    const val FAVORITES_PLAYLIST_ID = "favorites_default"
    private const val FAVORITES_PLAYLIST_NAME = "Favoritos"

    fun getAllPlaylists(context: Context): MutableList<Playlist> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PLAYLISTS, null) ?: return mutableListOf()

        val result = mutableListOf<Playlist>()
        val array = JSONArray(json)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.getString("id")
            val name = obj.getString("name")
            val songIdsArray = obj.getJSONArray("songIds")
            val songIds = mutableListOf<Long>()
            for (j in 0 until songIdsArray.length()) {
                songIds.add(songIdsArray.getLong(j))
            }
            val cover = obj.optString("cover", "")
            result.add(Playlist(id, name, songIds, if (cover.isBlank()) null else cover))
        }

        return result
    }

    private fun savePlaylists(context: Context, playlists: List<Playlist>) {
        val array = JSONArray()

        playlists.forEach { playlist ->
            val obj = JSONObject()
            obj.put("id", playlist.id)
            obj.put("name", playlist.name)
            val songIdsArray = JSONArray()
            playlist.songIds.forEach { songIdsArray.put(it) }
            obj.put("songIds", songIdsArray)
            obj.put("cover", playlist.coverImageUri ?: "")
            array.put(obj)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }

    /**
     * Crea la playlist "Favoritos" si todavia no existe. Se debe llamar una vez
     * al arrancar la pantalla principal para garantizar que siempre este disponible.
     */
    fun ensureFavoritesPlaylist(context: Context) {
        val playlists = getAllPlaylists(context)
        if (playlists.none { it.id == FAVORITES_PLAYLIST_ID }) {
            val favorites = Playlist(FAVORITES_PLAYLIST_ID, FAVORITES_PLAYLIST_NAME, mutableListOf())
            playlists.add(0, favorites)
            savePlaylists(context, playlists)
        }
    }

    fun isFavorite(context: Context, songId: Long): Boolean {
        val playlist = getAllPlaylists(context).find { it.id == FAVORITES_PLAYLIST_ID } ?: return false
        return playlist.songIds.contains(songId)
    }

    /**
     * Agrega o quita la cancion de "Favoritos". Devuelve true si quedo marcada
     * como favorita, false si se quito.
     */
    fun toggleFavorite(context: Context, songId: Long): Boolean {
        val playlists = getAllPlaylists(context)
        var favorites = playlists.find { it.id == FAVORITES_PLAYLIST_ID }
        if (favorites == null) {
            favorites = Playlist(FAVORITES_PLAYLIST_ID, FAVORITES_PLAYLIST_NAME, mutableListOf())
            playlists.add(0, favorites)
        }

        val isNowFavorite: Boolean
        if (favorites.songIds.contains(songId)) {
            favorites.songIds.remove(songId)
            isNowFavorite = false
        } else {
            favorites.songIds.add(songId)
            isNowFavorite = true
        }

        savePlaylists(context, playlists)
        return isNowFavorite
    }

    fun createPlaylist(context: Context, name: String): Playlist {
        val playlists = getAllPlaylists(context)
        val newPlaylist = Playlist(UUID.randomUUID().toString(), name, mutableListOf())
        playlists.add(newPlaylist)
        savePlaylists(context, playlists)
        return newPlaylist
    }

    fun deletePlaylist(context: Context, playlistId: String) {
        if (playlistId == FAVORITES_PLAYLIST_ID) return
        val playlists = getAllPlaylists(context)
        playlists.removeAll { it.id == playlistId }
        savePlaylists(context, playlists)
    }

    fun addSongToPlaylist(context: Context, playlistId: String, songId: Long) {
        val playlists = getAllPlaylists(context)
        val playlist = playlists.find { it.id == playlistId } ?: return
        if (!playlist.songIds.contains(songId)) {
            playlist.songIds.add(songId)
        }
        savePlaylists(context, playlists)
    }

    fun removeSongFromPlaylist(context: Context, playlistId: String, songId: Long) {
        val playlists = getAllPlaylists(context)
        val playlist = playlists.find { it.id == playlistId } ?: return
        playlist.songIds.remove(songId)
        savePlaylists(context, playlists)
    }

    fun getPlaylistById(context: Context, playlistId: String): Playlist? {
        return getAllPlaylists(context).find { it.id == playlistId }
    }

    fun setCoverImage(context: Context, playlistId: String, uri: String) {
        val playlists = getAllPlaylists(context)
        val playlist = playlists.find { it.id == playlistId } ?: return
        playlist.coverImageUri = uri
        savePlaylists(context, playlists)
    }
}