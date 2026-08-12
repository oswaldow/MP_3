package com.learnlayout.mp_3

import android.content.Context
import android.content.SharedPreferences

/**
 * Estado persistente del ecualizador de 10 bandas.
 * El procesamiento real se hace en SoftwareEqualizerProcessor dentro de
 * la cadena AudioProcessor de Media3/ExoPlayer.
 */
object EqualizerRepository {

    private const val PREFS_NAME = "equalizer_prefs"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_BAND_PREFIX = "eq_band_"
    private const val KEY_PREAMP = "eq_preamp"
    private const val KEY_AUTO_COMP = "eq_auto_compensation"

    private var prefs: SharedPreferences? = null
    private var initialized = false

    val isAvailable: Boolean
        get() = true

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val savedPrefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = savedPrefs

        SoftwareEqualizerProcessor.setMasterEnabled(
            savedPrefs.getBoolean(KEY_ENABLED, false)
        )

        for (band in 0 until SoftwareEqualizerProcessor.NUM_BANDS) {
            val savedLevel = savedPrefs.getInt(KEY_BAND_PREFIX + band, 0)
            SoftwareEqualizerProcessor.setBandGainMillibel(band, savedLevel)
        }

        SoftwareEqualizerProcessor.setPreampMillibel(
            savedPrefs.getInt(KEY_PREAMP, 0)
        )
        SoftwareEqualizerProcessor.setAutoCompensationEnabled(
            savedPrefs.getBoolean(KEY_AUTO_COMP, true)
        )
    }

    fun isEnabled(): Boolean = SoftwareEqualizerProcessor.isMasterEnabled()

    fun setEnabled(enabled: Boolean) {
        SoftwareEqualizerProcessor.setMasterEnabled(enabled)
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun getNumberOfBands(): Int = SoftwareEqualizerProcessor.NUM_BANDS

    fun getBandLevelRange(): ShortArray = shortArrayOf(
        SoftwareEqualizerProcessor.MIN_GAIN_MILLIBEL.toShort(),
        SoftwareEqualizerProcessor.MAX_GAIN_MILLIBEL.toShort()
    )

    fun getCenterFreqHz(band: Int): Int {
        if (band !in SoftwareEqualizerProcessor.CENTER_FREQS_HZ.indices) return 0
        return SoftwareEqualizerProcessor.CENTER_FREQS_HZ[band]
    }

    fun getCenterFrequenciesHz(): IntArray =
        SoftwareEqualizerProcessor.CENTER_FREQS_HZ.copyOf()

    fun getBandLevel(band: Int): Short =
        SoftwareEqualizerProcessor.getBandGainMillibel(band).toShort()

    fun setBandLevel(band: Int, level: Short) {
        SoftwareEqualizerProcessor.setBandGainMillibel(band, level.toInt())
        prefs?.edit()?.putInt(KEY_BAND_PREFIX + band, level.toInt())?.apply()
    }

    fun getPreampRange(): ShortArray = shortArrayOf(
        SoftwareEqualizerProcessor.MIN_PREAMP_MILLIBEL.toShort(),
        SoftwareEqualizerProcessor.MAX_PREAMP_MILLIBEL.toShort()
    )

    fun getPreampLevel(): Short =
        SoftwareEqualizerProcessor.getPreampMillibel().toShort()

    fun getPreampProgress(): Int {
        val range = getPreampRange()
        return getPreampLevel().toInt() - range[0].toInt()
    }

    fun setPreampLevel(level: Short) {
        SoftwareEqualizerProcessor.setPreampMillibel(level.toInt())
        prefs?.edit()?.putInt(KEY_PREAMP, level.toInt())?.apply()
    }

    fun isAutoCompensationEnabled(): Boolean =
        SoftwareEqualizerProcessor.isAutoCompensationEnabled()

    fun setAutoCompensationEnabled(enabled: Boolean) {
        SoftwareEqualizerProcessor.setAutoCompensationEnabled(enabled)
        prefs?.edit()?.putBoolean(KEY_AUTO_COMP, enabled)?.apply()
    }

    /** Deja bandas y preamp en 0 dB; activa la compensación automática. */
    fun resetAllBands() {
        SoftwareEqualizerProcessor.resetAllBands()
        SoftwareEqualizerProcessor.setPreampMillibel(0)
        SoftwareEqualizerProcessor.setAutoCompensationEnabled(true)

        val savedPrefs = prefs ?: return
        val editor = savedPrefs.edit()
        for (band in 0 until SoftwareEqualizerProcessor.NUM_BANDS) {
            editor.putInt(KEY_BAND_PREFIX + band, 0)
        }
        editor.putInt(KEY_PREAMP, 0)
        editor.putBoolean(KEY_AUTO_COMP, true)
        editor.apply()
    }
}