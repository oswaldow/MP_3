package com.learnlayout.mp_3

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log

/**
 * Estado persistente del ecualizador nativo de Android
 * (android.media.audiofx.Equalizer), mas BassBoost y Virtualizer.
 *
 * A diferencia de la version anterior (SoftwareEqualizerProcessor, un
 * AudioProcessor casero dentro de la cadena de Media3), esto delega el
 * filtrado real al DSP del propio chip de audio / fabricante, atado al
 * audioSessionId compartido de PlaybackEngine (ver
 * PlaybackEngine.getAudioSessionId() y MusicService.handleSongStarted(),
 * que llama a attachToSession()).
 *
 * En algunos fabricantes (MIUI/HyperOS y similares -ver el mismo
 * problema ya documentado en SpectrumAudioProcessor para el Visualizer
 * del sistema-) el motor de efectos de audio puede rechazar la creacion
 * de Equalizer/BassBoost/Virtualizer. Por eso cada uno se crea en su
 * propio try/catch: si Equalizer falla, isAvailable queda en false y la
 * UI muestra el estado "no disponible" (ver
 * EqualizerActivity.showUnavailableState()) sin fallback a software. Si
 * BassBoost o Virtualizer fallan por separado, sus controles quedan
 * ocultos pero el resto del ecualizador sigue funcionando normal.
 *
 * El preamp NO tiene equivalente en el Equalizer nativo (solo controla
 * bandas), asi que se resuelve fuera de el: se aplica como una ganancia
 * lineal extra dentro de ReplayGainAudioProcessor, que ya vive en la
 * cadena de AudioProcessor de Media3 (ver setPreampLevel() /
 * syncPreampToReplayGain() aqui abajo).
 */
object EqualizerRepository {

    private const val TAG = "MP3_EQ"

    private const val PREFS_NAME = "equalizer_prefs"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_BAND_PREFIX = "eq_band_"
    private const val KEY_PREAMP = "eq_preamp"
    private const val KEY_BASS_BOOST = "eq_bass_boost"
    private const val KEY_VIRTUALIZER = "eq_virtualizer"

    const val MIN_PREAMP_MILLIBEL = -1200
    const val MAX_PREAMP_MILLIBEL = 1200

    // Rango de BassBoost.setStrength()/Virtualizer.setStrength(): 0 (sin
    // efecto) a 1000 (maximo). Es fijo por especificacion de Android, no
    // depende del dispositivo (lo que si depende del dispositivo es si
    // "strength" es siquiera ajustable, ver isBassBoostAvailable/
    // isVirtualizerAvailable).
    const val MAX_EFFECT_STRENGTH = 1000

    private var prefs: SharedPreferences? = null
    private var initialized = false

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var attachedSessionId: Int = AudioManager.ERROR

    // Copia en memoria de lo guardado en SharedPreferences, disponible
    // desde init() aunque todavia no exista ningun efecto nativo creado
    // (attachToSession() no se llama hasta que hay una sesion de audio
    // real, ver comentario de clase). Es lo que se lee/pinta en la UI
    // antes de que arranque la primera cancion.
    private var pendingEnabled = false
    private var pendingBandLevelsMillibel: MutableMap<Int, Int> = mutableMapOf()
    private var pendingPreampMillibel = 0
    private var pendingBassBoostStrength: Short = 0
    private var pendingVirtualizerStrength: Short = 0

    val isAvailable: Boolean
        get() = equalizer != null

    val isBassBoostAvailable: Boolean
        get() = bassBoost?.strengthSupported == true

    val isVirtualizerAvailable: Boolean
        get() = virtualizer?.strengthSupported == true

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val savedPrefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = savedPrefs

        pendingEnabled = savedPrefs.getBoolean(KEY_ENABLED, false)
        pendingPreampMillibel = savedPrefs.getInt(KEY_PREAMP, 0)
            .coerceIn(MIN_PREAMP_MILLIBEL, MAX_PREAMP_MILLIBEL)
        pendingBassBoostStrength = savedPrefs.getInt(KEY_BASS_BOOST, 0)
            .coerceIn(0, MAX_EFFECT_STRENGTH).toShort()
        pendingVirtualizerStrength = savedPrefs.getInt(KEY_VIRTUALIZER, 0)
            .coerceIn(0, MAX_EFFECT_STRENGTH).toShort()

        for (band in 0 until 16) {
            if (savedPrefs.contains(KEY_BAND_PREFIX + band)) {
                pendingBandLevelsMillibel[band] = savedPrefs.getInt(KEY_BAND_PREFIX + band, 0)
            }
        }

        syncPreampToReplayGain()
    }

    /**
     * Crea Equalizer/BassBoost/Virtualizer atados a [sessionId] la
     * primera vez que hay una sesion de audio real. Llamarla de nuevo con
     * la MISMA sesion no hace nada (los efectos quedan atados a esa
     * sesion mientras viva el proceso, ver comentario de
     * PlaybackEngine.sharedAudioSessionId); si alguna vez llega una
     * sesion DISTINTA, se liberan los efectos viejos y se recrean.
     */
    fun attachToSession(sessionId: Int) {
        if (sessionId == AudioManager.ERROR || sessionId == 0) return
        if (sessionId == attachedSessionId && equalizer != null) return

        release()
        attachedSessionId = sessionId

        runCatching {
            val eq = Equalizer(0, sessionId)
            equalizer = eq
            for (band in 0 until eq.numberOfBands.toInt()) {
                val range = eq.bandLevelRange
                val saved = pendingBandLevelsMillibel[band] ?: 0
                val clamped = saved.coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                pendingBandLevelsMillibel[band] = clamped.toInt()
                eq.setBandLevel(band.toShort(), clamped)
            }
            eq.enabled = pendingEnabled
        }.onFailure {
            Log.w(TAG, "No se pudo crear Equalizer nativo en sesion $sessionId", it)
            equalizer = null
        }

        runCatching {
            val bb = BassBoost(0, sessionId)
            bassBoost = bb
            if (bb.strengthSupported) {
                bb.setStrength(pendingBassBoostStrength)
            }
            bb.enabled = pendingEnabled
        }.onFailure {
            Log.w(TAG, "No se pudo crear BassBoost en sesion $sessionId", it)
            bassBoost = null
        }

        runCatching {
            val vr = Virtualizer(0, sessionId)
            virtualizer = vr
            if (vr.strengthSupported) {
                vr.setStrength(pendingVirtualizerStrength)
            }
            vr.enabled = pendingEnabled
        }.onFailure {
            Log.w(TAG, "No se pudo crear Virtualizer en sesion $sessionId", it)
            virtualizer = null
        }

        syncPreampToReplayGain()
    }

    /** Libera los efectos nativos. Llamar desde MusicService.onDestroy(). */
    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
    }

    fun isEnabled(): Boolean = pendingEnabled

    fun setEnabled(enabled: Boolean) {
        pendingEnabled = enabled
        equalizer?.enabled = enabled
        if (isBassBoostAvailable) bassBoost?.enabled = enabled
        if (isVirtualizerAvailable) virtualizer?.enabled = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        syncPreampToReplayGain()
    }

    fun getNumberOfBands(): Int = equalizer?.numberOfBands?.toInt() ?: 0

    fun getBandLevelRange(): ShortArray = equalizer?.bandLevelRange ?: shortArrayOf(0, 0)

    fun getCenterFreqHz(band: Int): Int {
        val eq = equalizer ?: return 0
        if (band !in 0 until eq.numberOfBands.toInt()) return 0
        // getCenterFreq() devuelve milihercios.
        return eq.getCenterFreq(band.toShort()) / 1000
    }

    fun getCenterFrequenciesHz(): IntArray {
        val eq = equalizer ?: return IntArray(0)
        return IntArray(eq.numberOfBands.toInt()) { band -> eq.getCenterFreq(band.toShort()) / 1000 }
    }

    fun getBandLevel(band: Int): Short {
        val eq = equalizer ?: return 0
        if (band !in 0 until eq.numberOfBands.toInt()) return 0
        return eq.getBandLevel(band.toShort())
    }

    fun setBandLevel(band: Int, level: Short) {
        val eq = equalizer ?: return
        if (band !in 0 until eq.numberOfBands.toInt()) return
        eq.setBandLevel(band.toShort(), level)
        pendingBandLevelsMillibel[band] = level.toInt()
        prefs?.edit()?.putInt(KEY_BAND_PREFIX + band, level.toInt())?.apply()
    }

    fun getPreampRange(): ShortArray =
        shortArrayOf(MIN_PREAMP_MILLIBEL.toShort(), MAX_PREAMP_MILLIBEL.toShort())

    fun getPreampLevel(): Short = pendingPreampMillibel.toShort()

    fun getPreampProgress(): Int {
        val range = getPreampRange()
        return getPreampLevel().toInt() - range[0].toInt()
    }

    fun setPreampLevel(level: Short) {
        pendingPreampMillibel = level.toInt().coerceIn(MIN_PREAMP_MILLIBEL, MAX_PREAMP_MILLIBEL)
        prefs?.edit()?.putInt(KEY_PREAMP, pendingPreampMillibel)?.apply()
        syncPreampToReplayGain()
    }

    fun getBassBoostStrength(): Short = pendingBassBoostStrength

    fun setBassBoostStrength(strength: Short) {
        val clamped = strength.toInt().coerceIn(0, MAX_EFFECT_STRENGTH).toShort()
        pendingBassBoostStrength = clamped
        if (isBassBoostAvailable) bassBoost?.setStrength(clamped)
        prefs?.edit()?.putInt(KEY_BASS_BOOST, clamped.toInt())?.apply()
    }

    fun getVirtualizerStrength(): Short = pendingVirtualizerStrength

    fun setVirtualizerStrength(strength: Short) {
        val clamped = strength.toInt().coerceIn(0, MAX_EFFECT_STRENGTH).toShort()
        pendingVirtualizerStrength = clamped
        if (isVirtualizerAvailable) virtualizer?.setStrength(clamped)
        prefs?.edit()?.putInt(KEY_VIRTUALIZER, clamped.toInt())?.apply()
    }

    /** Deja bandas y preamp en 0 dB. No toca BassBoost/Virtualizer. */
    fun resetAllBands() {
        val eq = equalizer
        val editor = prefs?.edit()
        if (eq != null) {
            for (band in 0 until eq.numberOfBands.toInt()) {
                eq.setBandLevel(band.toShort(), 0)
                pendingBandLevelsMillibel[band] = 0
                editor?.putInt(KEY_BAND_PREFIX + band, 0)
            }
        }
        editor?.apply()
        setPreampLevel(0)
    }

    // El preamp solo debe sonar si el ecualizador esta activo Y
    // disponible; si no, ReplayGainAudioProcessor debe quedar en 0 dB de
    // preamp (ganancia neutra) aunque el usuario tenga guardado un valor
    // distinto de 0 para la proxima vez que lo active.
    private fun syncPreampToReplayGain() {
        val effectivePreamp = if (pendingEnabled) pendingPreampMillibel else 0
        ReplayGainAudioProcessor.setPreampMillibel(effectivePreamp)
    }
}