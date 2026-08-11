package com.learnlayout.mp_3

import android.content.Context
import android.content.SharedPreferences

/**
 * Fachada del ecualizador de 5 bandas. Antes envolvia un
 * android.media.audiofx.Equalizer del sistema (que en dispositivos
 * como el POCO F6 con HyperOS/MIUI rechaza la creacion del efecto con
 * Error -3, dejando el ecualizador "no disponible"). Ahora es un
 * ecualizador implementado en software (ver SoftwareEqualizerProcessor,
 * inyectado en el pipeline de ExoPlayer desde
 * EqAudioSinkRenderersFactory / MusicService.buildPlayer()), asi que
 * SIEMPRE esta disponible, sin depender de ningun servicio del sistema.
 *
 * La API publica (isAvailable, isEnabled, getBandLevel, etc.) se
 * mantiene igual que antes para no tener que tocar EqualizerActivity.
 *
 * Los niveles de banda se guardan en SharedPreferences apenas el
 * usuario mueve un slider, y se restauran solos la proxima vez que se
 * llama a init() (por ejemplo si el proceso murio y MusicService
 * arranca de nuevo).
 */
object EqualizerRepository {

    private const val PREFS_NAME = "equalizer_prefs"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_BAND_PREFIX = "eq_band_"

    private var prefs: SharedPreferences? = null
    private var initialized = false

    /** Siempre true: el ecualizador por software no depende del hardware. */
    val isAvailable: Boolean
        get() = true

    /**
     * Carga el estado guardado (enabled + niveles de banda) en el
     * procesador de audio. Se llama una sola vez, en
     * MusicService.onCreate(). A diferencia de la version anterior, ya
     * NO hace falta un audioSessionId: el procesador se inyecta directo
     * en el pipeline de cada ExoPlayer (ver EqAudioSinkRenderersFactory).
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val savedPrefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = savedPrefs

        SoftwareEqualizerProcessor.setMasterEnabled(savedPrefs.getBoolean(KEY_ENABLED, false))
        for (band in 0 until SoftwareEqualizerProcessor.NUM_BANDS) {
            val savedLevel = savedPrefs.getInt(KEY_BAND_PREFIX + band, 0)
            SoftwareEqualizerProcessor.setBandGainMillibel(band, savedLevel)
        }
    }

    fun isEnabled(): Boolean = SoftwareEqualizerProcessor.isMasterEnabled()

    fun setEnabled(enabled: Boolean) {
        SoftwareEqualizerProcessor.setMasterEnabled(enabled)
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun getNumberOfBands(): Int = SoftwareEqualizerProcessor.NUM_BANDS

    /** [min, max] en milibeles (mB). 100 mB = 1 dB. */
    fun getBandLevelRange(): ShortArray = shortArrayOf(
        SoftwareEqualizerProcessor.MIN_GAIN_MILLIBEL.toShort(),
        SoftwareEqualizerProcessor.MAX_GAIN_MILLIBEL.toShort()
    )

    /** Frecuencia central de la banda, en Hz. */
    fun getCenterFreqHz(band: Int): Int {
        if (band !in SoftwareEqualizerProcessor.CENTER_FREQS_HZ.indices) return 0
        return SoftwareEqualizerProcessor.CENTER_FREQS_HZ[band]
    }

    fun getBandLevel(band: Int): Short =
        SoftwareEqualizerProcessor.getBandGainMillibel(band).toShort()

    fun setBandLevel(band: Int, level: Short) {
        SoftwareEqualizerProcessor.setBandGainMillibel(band, level.toInt())
        prefs?.edit()?.putInt(KEY_BAND_PREFIX + band, level.toInt())?.apply()
    }

    /** Deja todas las bandas en 0 dB (plano), sin tocar el estado enabled/disabled. */
    fun resetAllBands() {
        SoftwareEqualizerProcessor.resetAllBands()
        val savedPrefs = prefs ?: return
        val editor = savedPrefs.edit()
        for (band in 0 until SoftwareEqualizerProcessor.NUM_BANDS) {
            editor.putInt(KEY_BAND_PREFIX + band, 0)
        }
        editor.apply()
    }
}