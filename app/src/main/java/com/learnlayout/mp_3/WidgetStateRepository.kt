package com.learnlayout.mp_3

import android.content.Context

/**
 * Guarda el ultimo estado conocido de reproduccion (titulo, artista, si
 * esta sonando, posicion/duracion y modo de reproduccion) para que el
 * widget pueda pintarse de inmediato al agregarse o al reiniciar el
 * telefono, sin depender de que MusicService este vivo. El songId es lo
 * que le permite a MusicWidgetProvider volver a pedirle la caratula a
 * AlbumArtRepository (que ya la tiene en su cache de disco) sin
 * necesidad del objeto Song completo.
 */
object WidgetStateRepository {

    private const val PREFS_NAME = "widget_state_prefs"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_HAS_SONG = "has_song"
    private const val KEY_SONG_ID = "song_id"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_DURATION_MS = "duration_ms"
    private const val KEY_PLAYBACK_MODE = "playback_mode"

    data class WidgetState(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val hasSong: Boolean,
        val songId: Long = -1L,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val playbackMode: MusicService.PlaybackMode = MusicService.PlaybackMode.NORMAL
    )

    fun saveState(context: Context, state: WidgetState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TITLE, state.title)
            .putString(KEY_ARTIST, state.artist)
            .putBoolean(KEY_IS_PLAYING, state.isPlaying)
            .putBoolean(KEY_HAS_SONG, state.hasSong)
            .putLong(KEY_SONG_ID, state.songId)
            .putLong(KEY_POSITION_MS, state.positionMs)
            .putLong(KEY_DURATION_MS, state.durationMs)
            .putString(KEY_PLAYBACK_MODE, state.playbackMode.name)
            .apply()
    }

    fun getState(context: Context): WidgetState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMode = prefs.getString(KEY_PLAYBACK_MODE, null)
        val mode = savedMode?.let {
            runCatching { MusicService.PlaybackMode.valueOf(it) }.getOrNull()
        } ?: MusicService.PlaybackMode.NORMAL

        return WidgetState(
            title = prefs.getString(KEY_TITLE, null) ?: "MP_3",
            artist = prefs.getString(KEY_ARTIST, null) ?: "Abre la app para reproducir musica",
            isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false),
            hasSong = prefs.getBoolean(KEY_HAS_SONG, false),
            songId = prefs.getLong(KEY_SONG_ID, -1L),
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L),
            durationMs = prefs.getLong(KEY_DURATION_MS, 0L),
            playbackMode = mode
        )
    }
}