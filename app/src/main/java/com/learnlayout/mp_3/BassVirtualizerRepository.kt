package com.learnlayout.mp_3

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.util.Log

/**
 * Estado persistente y aplicacion en vivo de Bass Boost y Virtualizador.
 *
 * A diferencia del ecualizador de 10 bandas (que es software, ver
 * SoftwareEqualizerProcessor dentro de la cadena AudioProcessor de
 * Media3), Bass Boost y Virtualizador son efectos NATIVOS del sistema
 * (android.media.audiofx) que se enganchan por audioSessionId a la
 * sesion de audio COMPARTIDA que ya expone
 * PlaybackEngine.getAudioSessionId() / MusicService.getAudioSessionId().
 * Al vivir "por sesion" y no "por player", siguen aplicando sin
 * importar cual ExoPlayer interno este sonando en cada momento (cancion
 * normal, restore o el segundo player del crossfade), igual que ya
 * pasaba con el EQ antiguo basado en sesion.
 *
 * IMPORTANTE: hay que llamar a attachToSession() en cuanto se conozca
 * un audioSessionId valido (EqualizerActivity lo hace en
 * onServiceConnected). Si esta pantalla se abre antes de que haya
 * sonado una sola cancion en el servicio, sessionId todavia es
 * AudioManager.ERROR: los sliders siguen guardando el valor con
 * set*Strength() con toda normalidad, solo que no hay ningun efecto
 * real corriendo todavia para aplicarselo hasta que exista una sesion.
 */
object BassVirtualizerRepository {

    private const val TAG = "BassVirtualizerRepo"
    private const val PREFS_NAME = "bass_virtualizer_prefs"
    private const val KEY_ENABLED = "bv_enabled"
    private const val KEY_BASS = "bv_bass_strength"
    private const val KEY_VIRTUALIZER = "bv_virtualizer_strength"

    private var prefs: SharedPreferences? = null
    private var initialized = false

    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var attachedSessionId: Int = AudioManager.ERROR

    private var masterEnabled: Boolean = true
    private var bassStrength: Int = 0
    private var virtualizerStrength: Int = 0

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val savedPrefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = savedPrefs

        masterEnabled = savedPrefs.getBoolean(KEY_ENABLED, true)
        bassStrength = savedPrefs.getInt(KEY_BASS, 0)
        virtualizerStrength = savedPrefs.getInt(KEY_VIRTUALIZER, 0)
    }

    /**
     * Crea (o reutiliza) BassBoost/Virtualizer sobre [sessionId] y les
     * aplica de inmediato el estado guardado (enabled + fuerza). Segura
     * de llamar varias veces seguidas: si ya estan enganchados a esta
     * misma sesion no hace nada, y si la sesion cambia libera los
     * efectos viejos antes de crear los nuevos.
     */
    fun attachToSession(sessionId: Int) {
        if (sessionId == AudioManager.ERROR || sessionId == attachedSessionId) return

        // DEBUG: mismo tag que PlaybackEngine (MP3_XFADE) para poder ver,
        // en un solo logcat filtrado, si este attach() cae justo en medio
        // de un crossfade (dos AudioTrack activos en la misma sesion) y
        // correlacionarlo con onAudioTrackInitialized/onAudioUnderrun de
        // attachAudioDiagnostics().
        Log.w(
            PlaybackEngine.TAG_XFADE,
            "BassVirtualizerRepository.attachToSession: sessionId=$sessionId " +
                    "(anterior=$attachedSessionId) masterEnabled=$masterEnabled " +
                    "bassStrength=$bassStrength virtualizerStrength=$virtualizerStrength"
        )

        releaseEffects()

        try {
            bassBoost = BassBoost(0, sessionId).apply {
                enabled = masterEnabled
                if (strengthSupported) setStrength(bassStrength.toShort())
            }
        } catch (e: Exception) {
            // Algunos dispositivos/fabricantes no traen BassBoost
            // disponible: no queremos tumbar la pantalla por eso, solo
            // se queda sin aplicar el efecto (los sliders igual guardan
            // el valor por si el dispositivo lo soporta mas adelante).
            Log.w(TAG, "No se pudo crear BassBoost en sesion $sessionId", e)
            bassBoost = null
        }

        try {
            virtualizer = Virtualizer(0, sessionId).apply {
                enabled = masterEnabled
                if (strengthSupported) setStrength(virtualizerStrength.toShort())
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo crear Virtualizer en sesion $sessionId", e)
            virtualizer = null
        }

        attachedSessionId = sessionId
        Log.w(PlaybackEngine.TAG_XFADE, "BassVirtualizerRepository.attachToSession: TERMINADO para sessionId=$sessionId")
    }

    private fun releaseEffects() {
        try {
            bassBoost?.release()
        } catch (_: Exception) { }
        try {
            virtualizer?.release()
        } catch (_: Exception) { }
        bassBoost = null
        virtualizer = null
        attachedSessionId = AudioManager.ERROR
    }

    fun getBassBoostStrength(): Int = bassStrength

    fun setBassBoostStrength(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        prefs?.edit()?.putInt(KEY_BASS, bassStrength)?.apply()
        try {
            bassBoost?.takeIf { it.strengthSupported }?.setStrength(bassStrength.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo aplicar fuerza de BassBoost", e)
        }
    }

    fun getVirtualizerStrength(): Int = virtualizerStrength

    fun setVirtualizerStrength(strength: Int) {
        virtualizerStrength = strength.coerceIn(0, 1000)
        prefs?.edit()?.putInt(KEY_VIRTUALIZER, virtualizerStrength)?.apply()
        try {
            virtualizer?.takeIf { it.strengthSupported }?.setStrength(virtualizerStrength.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo aplicar fuerza de Virtualizer", e)
        }
    }

    /** Sigue al switch general del Ecualizador (misma idea que EqualizerRepository.setEnabled). */
    fun setMasterEnabled(enabled: Boolean) {
        masterEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        Log.w(PlaybackEngine.TAG_XFADE, "BassVirtualizerRepository.setMasterEnabled: $enabled (attachedSessionId=$attachedSessionId)")
        try {
            bassBoost?.enabled = enabled
            virtualizer?.enabled = enabled
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cambiar enabled de BassBoost/Virtualizer", e)
        }
    }

    /** DEBUG: snapshot de si el switch maestro de Bass/Virtualizer esta activo, para logs de PlaybackEngine. */
    fun isMasterEnabled(): Boolean = masterEnabled

    /** Deja ambos efectos en 0 (sin boost/virtualizacion), igual que "Restablecer" del EQ. */
    fun reset() {
        setBassBoostStrength(0)
        setVirtualizerStrength(0)
    }

    /**
     * Libera los efectos nativos. Llamar desde MusicService.onDestroy()
     * (no desde EqualizerActivity: estos efectos viven mientras vive la
     * sesion de reproduccion, no mientras esta abierta la pantalla del
     * ecualizador).
     */
    fun release() {
        releaseEffects()
    }
}