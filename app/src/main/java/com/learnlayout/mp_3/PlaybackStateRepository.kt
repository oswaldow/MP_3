package com.learnlayout.mp_3

import android.content.Context

object PlaybackStateRepository {

    private const val PREFS_NAME = "mp3_playback_state"
    private const val KEY_LAST_SONG_ID = "last_song_id"
    private const val KEY_LAST_POSITION_MS = "last_position_ms"
    private const val KEY_SORT_TYPE = "sort_type"

    private const val NO_SONG_ID = -1L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLastSong(context: Context, songId: Long, positionMs: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_SONG_ID, songId)
            .putLong(KEY_LAST_POSITION_MS, positionMs)
            .apply()
    }

    // Version bloqueante (commit() en vez de apply()): se usa unicamente en
    // onTaskRemoved()/onDestroy() de MusicService, momentos en los que el
    // proceso puede morir en cualquier instante y no hay garantia de que
    // la escritura asincrona de apply() alcance a llegar a disco.
    fun saveLastSongBlocking(context: Context, songId: Long, positionMs: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_SONG_ID, songId)
            .putLong(KEY_LAST_POSITION_MS, positionMs)
            .commit()
    }

    fun getLastSongId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SONG_ID, NO_SONG_ID)

    fun getLastPositionMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_POSITION_MS, 0L)

    fun clearLastSong(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_SONG_ID)
            .remove(KEY_LAST_POSITION_MS)
            .apply()
    }

    fun saveSortType(context: Context, sortType: SongListActivity.SortType) {
        prefs(context).edit()
            .putString(KEY_SORT_TYPE, sortType.name)
            .apply()
    }

    fun getSortType(context: Context): SongListActivity.SortType {
        val name = prefs(context).getString(KEY_SORT_TYPE, null)
            ?: return SongListActivity.SortType.TITLE
        return try {
            SongListActivity.SortType.valueOf(name)
        } catch (e: IllegalArgumentException) {
            SongListActivity.SortType.TITLE
        }
    }
}