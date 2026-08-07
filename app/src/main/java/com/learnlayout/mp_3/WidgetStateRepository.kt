package com.learnlayout.mp_3

import android.content.Context

/**
 * Guarda el ultimo estado conocido de reproduccion (titulo, artista, si
 * esta sonando) para que el widget pueda pintarse de inmediato al agregarse
 * o al reiniciar el telefono, sin depender de que MusicService este vivo.
 */
object WidgetStateRepository {

    private const val PREFS_NAME = "widget_state_prefs"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_HAS_SONG = "has_song"

    data class WidgetState(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val hasSong: Boolean
    )

    fun saveState(context: Context, state: WidgetState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TITLE, state.title)
            .putString(KEY_ARTIST, state.artist)
            .putBoolean(KEY_IS_PLAYING, state.isPlaying)
            .putBoolean(KEY_HAS_SONG, state.hasSong)
            .apply()
    }

    fun getState(context: Context): WidgetState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WidgetState(
            title = prefs.getString(KEY_TITLE, null) ?: "MP_3",
            artist = prefs.getString(KEY_ARTIST, null) ?: "Abre la app para reproducir musica",
            isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false),
            hasSong = prefs.getBoolean(KEY_HAS_SONG, false)
        )
    }
}