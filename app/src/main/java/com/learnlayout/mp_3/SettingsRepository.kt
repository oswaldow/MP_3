package com.learnlayout.mp_3

import android.content.Context

// Guarda las preferencias de configuracion de la app (crossfade, lectura de
// WhatsApp, voz de lectura, normalizacion de volumen). Se usa tanto desde
// SettingsActivity (para escribir) como desde MusicService/PlaybackEngine/
// WhatsAppNotificationReaderService (para leer).
object SettingsRepository {

    private const val PREFS_NAME = "mp3_settings"
    private const val KEY_CROSSFADE_ENABLED = "crossfade_enabled"
    private const val KEY_CROSSFADE_SECONDS = "crossfade_seconds"
    private const val KEY_WHATSAPP_READING_ENABLED = "whatsapp_reading_enabled"
    private const val KEY_TTS_VOICE_NAME = "tts_voice_name"
    private const val KEY_VOLUME_NORMALIZATION_ENABLED = "volume_normalization_enabled"
    private const val KEY_VOLUME_NORMALIZATION_GAIN_MILLIBEL = "volume_normalization_gain_millibel"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    const val MIN_CROSSFADE_SECONDS = 5
    const val MAX_CROSSFADE_SECONDS = 20
    const val DEFAULT_CROSSFADE_SECONDS = 12

    // Rango de la ganancia extra manual de "Normalizar volumen": una vez
    // que cada cancion ya quedo pareja (ver SongGainRepository), esto deja
    // subir o bajar el volumen resultante de TODAS las canciones por igual,
    // en milibeles (100 mB = 1 dB). Mismo rango logico que el preamp del
    // ecualizador, pero mas acotado porque aqui es un ajuste fino sobre
    // audio ya normalizado, no una correccion grande.
    const val MIN_VOLUME_NORMALIZATION_GAIN_MILLIBEL = -600
    const val MAX_VOLUME_NORMALIZATION_GAIN_MILLIBEL = 600

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

    fun isWhatsAppReadingEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_WHATSAPP_READING_ENABLED, false)
    }

    fun setWhatsAppReadingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WHATSAPP_READING_ENABLED, enabled).apply()
    }

    /** Nombre interno (TextToSpeech.Voice.name) de la voz elegida para leer
     * mensajes, o null si se debe usar la voz predeterminada del sistema. */
    fun getTtsVoiceName(context: Context): String? {
        return prefs(context).getString(KEY_TTS_VOICE_NAME, null)
    }

    fun setTtsVoiceName(context: Context, voiceName: String?) {
        prefs(context).edit().putString(KEY_TTS_VOICE_NAME, voiceName).apply()
    }

    fun isVolumeNormalizationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_VOLUME_NORMALIZATION_ENABLED, false)
    }

    fun setVolumeNormalizationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOLUME_NORMALIZATION_ENABLED, enabled).apply()
    }

    /** Ganancia extra manual (milibeles) que el usuario le suma o resta al
     * volumen ya normalizado de todas las canciones. 0 = sin cambio. */
    fun getVolumeNormalizationGainMillibel(context: Context): Int {
        val saved = prefs(context).getInt(KEY_VOLUME_NORMALIZATION_GAIN_MILLIBEL, 0)
        return saved.coerceIn(MIN_VOLUME_NORMALIZATION_GAIN_MILLIBEL, MAX_VOLUME_NORMALIZATION_GAIN_MILLIBEL)
    }

    fun setVolumeNormalizationGainMillibel(context: Context, millibel: Int) {
        val clamped = millibel.coerceIn(MIN_VOLUME_NORMALIZATION_GAIN_MILLIBEL, MAX_VOLUME_NORMALIZATION_GAIN_MILLIBEL)
        prefs(context).edit().putInt(KEY_VOLUME_NORMALIZATION_GAIN_MILLIBEL, clamped).apply()
    }

    /** true si la persona ya vio (o salto) las pantallas de bienvenida. */
    fun isOnboardingCompleted(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }
}