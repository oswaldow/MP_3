package com.learnlayout.mp_3

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Centraliza la carga, filtrado, ordenamiento y resolución de canciones para
 * la biblioteca y las playlists, sin manejar navegación ni reproducción.
 */
class SongLibraryController(
    private val context: Context,
    private val songAdapter: SongAdapter,
    private val playlistAdapter: PlaylistAdapter,
    private val emptyState: TextView,
    private val songsRecyclerView: RecyclerView,
    private val isPlaylistsTabActive: () -> Boolean,
    private val isHomeVisible: () -> Boolean,
    private val onHomeRefresh: () -> Unit,
    private val getCurrentPlayingSong: () -> Song?
) {

    var allSongs: List<Song> = emptyList()
        private set

    private var currentSort = SongListActivity.SortType.TITLE
    private var searchQuery = ""

    fun setSort(type: SongListActivity.SortType) {
        currentSort = type
    }

    fun setSearchQuery(query: String) {
        searchQuery = query
        applyFilterAndSort()
    }

    fun loadSongs() {
        val rawSongs = SongRepository.getAllSongs(context)
        val overrides = SongMetadataRepository.getAllOverrides(context)

        allSongs = rawSongs.map { song ->
            overrides[song.id]?.let { override ->
                song.copy(
                    title = override.first.ifBlank { song.title },
                    artist = override.second.ifBlank { song.artist }
                )
            } ?: song
        }

        if (allSongs.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            songsRecyclerView.visibility = View.GONE
            return
        }

        applyFilterAndSort()
        if (isHomeVisible()) onHomeRefresh()
    }

    fun refreshSongs() {
        applyFilterAndSort()
    }

    fun loadPlaylists() {
        playlistAdapter.updateData(
            buildAutoPlaylists() + PlaylistRepository.getAllPlaylists(context)
        )
    }

    fun getSongsForPlaylist(playlist: Playlist): List<Song> {
        val songsById = allSongs.associateBy { it.id }
        return playlist.songIds.mapNotNull(songsById::get)
    }

    fun findSongById(songId: Long): Song? {
        return allSongs.firstOrNull { it.id == songId }
    }

    private fun applyFilterAndSort() {
        if (isPlaylistsTabActive()) return

        val filtered = if (searchQuery.isBlank()) {
            allSongs
        } else {
            allSongs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.artist.contains(searchQuery, ignoreCase = true)
            }
        }

        val sorted = when (currentSort) {
            SongListActivity.SortType.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SongListActivity.SortType.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            SongListActivity.SortType.DURATION -> filtered.sortedBy { it.duration }
            SongListActivity.SortType.DATE_ADDED -> filtered.sortedByDescending { it.dateAdded }
            SongListActivity.SortType.MOST_PLAYED -> {
                val counts = PlayCountRepository.getAllPlayCounts(context)
                filtered.sortedByDescending { counts[it.id] ?: 0 }
            }
        }

        songAdapter.updateData(sorted)

        val hasResults = sorted.isNotEmpty()
        emptyState.visibility = if (hasResults) View.GONE else View.VISIBLE

        if (isHomeVisible()) {
            songsRecyclerView.visibility = View.GONE
        } else {
            songsRecyclerView.visibility = if (hasResults) View.VISIBLE else View.GONE
        }

        getCurrentPlayingSong()?.let { songAdapter.setCurrentPlayingId(it.id) }
    }

    private fun buildAutoPlaylists(): List<Playlist> {
        val playlists = mutableListOf<Playlist>()

        PlayCountRepository.getRecentlyPlayedSongIds(context, AUTO_PLAYLIST_LIMIT)
            .takeIf { it.isNotEmpty() }
            ?.let { ids ->
                playlists += Playlist(
                    SongListActivity.RECENT_PLAYLIST_ID,
                    SongListActivity.RECENT_PLAYLIST_NAME,
                    ids.toMutableList()
                )
            }

        PlayCountRepository.getMostPlayedSongIds(context, AUTO_PLAYLIST_LIMIT)
            .takeIf { it.isNotEmpty() }
            ?.let { ids ->
                playlists += Playlist(
                    SongListActivity.MOST_PLAYED_PLAYLIST_ID,
                    SongListActivity.MOST_PLAYED_PLAYLIST_NAME,
                    ids.toMutableList()
                )
            }

        return playlists
    }

    private companion object {
        const val AUTO_PLAYLIST_LIMIT = 50
    }
}