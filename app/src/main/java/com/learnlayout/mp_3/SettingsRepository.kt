package com.learnlayout.mp_3

import android.content.Context

// Guarda las preferencias de configuracion de la app (por ahora, todo lo
// relacionado a crossfade). Se usa tanto desde SettingsActivity (para
// escribir) como desde MusicService (para leer, en cada actualizacion de
// progreso).
object SettingsRepository {

    private const val PREFS_NAME = "mp3_settings"
    private const val KEY_CROSSFADE_ENABLED = "crossfade_enabled"
    private const val KEY_CROSSFADE_SECONDS = "crossfade_seconds"

    const val MIN_CROSSFADE_SECONDS = 5
    const val MAX_CROSSFADE_SECONDS = 20
    const val DEFAULT_CROSSFADE_SECONDS = 12

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCrossfadeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CROSSFADE_ENABLED, false)
    }

    fun setCrossfadeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CROSSFADE_ENABLED, enabled).apply()
    }

    fun getCrossfadeSeconds(context: Context): Int {
        val saved = prefs(context).getInt(KEY_CROSSFADE_SECONDS, DEFAULT_CROSSFADE_SECONDS)
        return saved.coerceIn(MIN_CROSSFADE_SECONDS, MAX_CROSSFADE_SECONDS)
    }

    fun setCrossfadeSeconds(context: Context, seconds: Int) {
        val clamped = seconds.coerceIn(MIN_CROSSFADE_SECONDS, MAX_CROSSFADE_SECONDS)
        prefs(context).edit().putInt(KEY_CROSSFADE_SECONDS, clamped).apply()
    }
}