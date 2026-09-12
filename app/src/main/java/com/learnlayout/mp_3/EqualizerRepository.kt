package com.learnlayout.mp_3

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.util.Log

/**
 * Estado persistente del ecualizador de 10 bandas, mas BassBoost y
 * Virtualizer.
 *
 * El ecualizador de bandas YA NO delega en android.media.audiofx.Equalizer
 * (el DSP nativo del fabricante): eso hacia que el efecto real dependiera
 * por completo del chip de audio de cada telefono -en equipos de gama
 * media el rango de dB disponible solia ser muy limitado ("efecto debil"),
 * y el DSP del fabricante podia sonar sucio al subir varias bandas
 * ("raro"/distorsionado), sin ningun control desde la app sobre eso-.
 *
 * Ahora las 10 bandas vuelven a ser 100% software (ver
 * SoftwareEqualizerProcessor, un AudioProcessor propio dentro de la
 * cadena de Media3, con headroom calculado a partir de la respuesta real
 * combinada de las bandas en vez de una regla fija). El resultado es
 * identico sin importar el telefono, igual que ya se hizo con
 * SpectrumAudioProcessor por el mismo motivo (en varios fabricantes,
 * MIUI/HyperOS y similares, el motor de efectos de audio del sistema
 * llega a rechazar la creacion de efectos de terceros).
 *
 * BassBoost y Virtualizer SI se quedan como efectos nativos
 * (android.media.audiofx): son binarios mucho mas simples que un
 * ecualizador de 10 bandas, y ya tenian su propio fallback a "no
 * disponible" cuando el fabricante los rechaza, atados al
 * audioSessionId compartido de PlaybackEngine.
 *
 * El preamp NO tiene equivalente en las bandas (solo agrega una ganancia
 * manual extra encima), asi que se resuelve fuera de ellas: se aplica
 * como una ganancia lineal dentro de PreampAudioProcessor, que corre
 * ANTES que el EQ en la cadena de AudioProcessor de Media3 (ver
 * setPreampLevel() / syncPreampToProcessor() aqui abajo, y
 * EqAudioSinkRenderersFactory para el orden de la cadena).
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

    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var attachedSessionId: Int = AudioManager.ERROR

    // Copia en memoria de lo guardado en SharedPreferences, disponible
    // desde init() aunque todavia no haya sonado ninguna cancion. Es lo
    // que se lee/pinta en la UI antes de que arranque la primera cancion.
    private var pendingEnabled = false
    private var pendingBandLevelsMillibel: MutableMap<Int, Int> = mutableMapOf()
    private var pendingPreampMillibel = 0
    private var pendingBassBoostStrength: Short = 0
    private var pendingVirtualizerStrength: Short = 0

    /** El EQ de 10 bandas es software puro: siempre disponible, en cualquier telefono. */
    val isAvailable: Boolean = true

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

        for (band in 0 until SoftwareEqualizerProcessor.NUM_BANDS) {
            val saved = savedPrefs.getInt(KEY_BAND_PREFIX + band, 0)
                .coerceIn(SoftwareEqualizerProcessor.MIN_GAIN_MILLIBEL, SoftwareEqualizerProcessor.MAX_GAIN_MILLIBEL)
            pendingBandLevelsMillibel[band] = saved
            SoftwareEqualizerProcessor.setBandGainMillibel(band, saved)
        }

        SoftwareEqualizerProcessor.setMasterEnabled(pendingEnabled)
        syncPreampToProcessor()
    }

    /**
     * Crea BassBoost/Virtualizer atados a [sessionId] la primera vez que
     * hay una sesion de audio real. El EQ de 10 bandas YA NO depende de
     * la sesion: SoftwareEqualizerProcessor vive dentro de la cadena de
     * AudioProcessor y aplica su estado global sin importar que instancia
     * de ExoPlayer este sonando en cada momento, igual que ya pasaba con
     * PreampAudioProcessor.
     */
    fun attachToSession(sessionId: Int) {
        if (sessionId == AudioManager.ERROR || sessionId == 0) return
        if (sessionId == attachedSessionId && (bassBoost != null || virtualizer != null)) return

        release()
        attachedSessionId = sessionId

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
    }

    /** Libera los efectos nativos. Llamar desde MusicService.onDestroy(). */
    fun release() {
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        bassBoost = null
        virtualizer = null
    }

    fun isEnabled(): Boolean = pendingEnabled

    fun setEnabled(enabled: Boolean) {
        pendingEnabled = enabled
        SoftwareEqualizerProcessor.setMasterEnabled(enabled)
        if (isBassBoostAvailable) bassBoost?.enabled = enabled
        if (isVirtualizerAvailable) virtualizer?.enabled = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        syncPreampToProcessor()
    }

    fun getNumberOfBands(): Int = SoftwareEqualizerProcessor.NUM_BANDS

    fun getBandLevelRange(): ShortArray = shortArrayOf(
        SoftwareEqualizerProcessor.MIN_GAIN_MILLIBEL.toShort(),
        SoftwareEqualizerProcessor.MAX_GAIN_MILLIBEL.toShort()
    )

    fun getCenterFreqHz(band: Int): Int {
        if (band !in 0 until SoftwareEqualizerProcessor.NUM_BANDS) return 0
        return SoftwareEqualizerProcessor.CENTER_FREQS_HZ[band]
    }

    fun getCenterFrequenciesHz(): IntArray = SoftwareEqualizerProcessor.CENTER_FREQS_HZ.copyOf()

    fun getBandLevel(band: Int): Short {
        if (band !in 0 until SoftwareEqualizerProcessor.NUM_BANDS) return 0
        return (pendingBandLevelsMillibel[band] ?: 0).toShort()
    }

    fun setBandLevel(band: Int, level: Short) {
        if (band !in 0 until SoftwareEqualizerProcessor.NUM_BANDS) return
        val clamped = level.toInt().coerceIn(
            SoftwareEqualizerProcessor.MIN_GAIN_MILLIBEL,
            SoftwareEqualizerProcessor.MAX_GAIN_MILLIBEL
        )
        pendingBandLevelsMillibel[band] = clamped
        SoftwareEqualizerProcessor.setBandGainMillibel(band, clamped)
        prefs?.edit()?.putInt(KEY_BAND_PREFIX + band, clamped)?.apply()
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
        syncPreampToProcessor()
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
        val editor = prefs?.edit()
        for (band in 0 until SoftwareEqualizerProcessor.NUM_BANDS) {
            pendingBandLevelsMillibel[band] = 0
            SoftwareEqualizerProcessor.setBandGainMillibel(band, 0)
            editor?.putInt(KEY_BAND_PREFIX + band, 0)
        }
        editor?.apply()
        setPreampLevel(0)
    }

    // El preamp solo debe sonar si el ecualizador esta activo; si no,
    // PreampAudioProcessor debe quedar en 0 dB de preamp (ganancia
    // neutra) aunque el usuario tenga guardado un valor distinto de 0
    // para la proxima vez que lo active.
    private fun syncPreampToProcessor() {
        val effectivePreamp = if (pendingEnabled) pendingPreampMillibel else 0
        PreampAudioProcessor.setPreampMillibel(effectivePreamp)
    }
}