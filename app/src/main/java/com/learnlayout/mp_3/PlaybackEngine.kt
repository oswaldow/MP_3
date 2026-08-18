package com.learnlayout.mp_3

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlin.math.cos
import kotlin.math.sin

class PlaybackEngine(
    private val context: Context,
    private val handler: Handler,
    private val callback: Callback
) {
    enum class SongStartReason { NEW, RESTORED, CROSSFADE }

    interface Callback {
        fun songAt(index: Int): Song?
        fun nextIndexFrom(index: Int): Int
        fun queueSize(): Int
        fun isRepeatOneMode(): Boolean
        fun isBlockedBySleepTimerEndOfSong(): Boolean

        /** Se llama cuando arranca de verdad una cancion nueva, restaurada o tras un crossfade. */
        fun onSongStarted(song: Song, index: Int, reason: SongStartReason)

        fun onProgress(currentMs: Int, totalMs: Int)
        fun onPlaybackStateChanged(isPlaying: Boolean)

        /** La cancion actual llego al final SIN haber hecho crossfade (cancion corta, crossfade desactivado, etc). */
        fun onTrackEnded()
    }

    private var mediaPlayer: ExoPlayer? = null
    private var loadedIndex: Int = -1

    // Una sola sesion de audio para TODOS los ExoPlayer que crea este
    // motor (normal, restore, crossfade). Ya no hace falta para el
    // ecualizador (que ahora es por software, ver
    // SoftwareEqualizerProcessor / EqAudioSinkRenderersFactory), pero se
    // mantiene por si en el futuro se necesita para otro efecto de audio
    // del sistema. Ver buildPlayer().
    private var sharedAudioSessionId: Int = AudioManager.ERROR

    private var progressRunnable: Runnable? = null

    // --- Crossfade ---
    // Mientras se hace crossfade hay DOS ExoPlayer sonando a la vez:
    // "mediaPlayer" (la cancion que esta terminando) y "nextMediaPlayer"
    // (la cancion que ya empezo a sonar bajito y va subiendo de volumen).
    private var nextMediaPlayer: ExoPlayer? = null
    private var nextIndexDuringCrossfade: Int = -1
    private var isCrossfading: Boolean = false
    private var crossfadeRunnable: Runnable? = null
    private var crossfadeTotalMs: Long = 0L
    private var crossfadeElapsedMs: Long = 0L

    // --- Fundido en pausa / play y en cambio manual de cancion ---
    // Independiente del crossfade automatico de arriba (que solo aplica
    // cerca del final de una cancion). Estos fundidos son cortos y se
    // usan para que pausar/reanudar y saltar de cancion a mano
    // (siguiente/anterior/tocar una de la lista) no suenen como un corte
    // seco.
    private var pauseFadeRunnable: Runnable? = null
    private var playFadeRunnable: Runnable? = null
    private var manualFadeOutRunnable: Runnable? = null
    private var manualFadeInRunnable: Runnable? = null

    private var preparedNextPlayer: ExoPlayer? = null
    private var preparedNextIndex: Int = -1
    private var prepareRequestedForIndex: Int = -1

    // Marca de tiempo del ultimo tick del progressRunnable (cada 500ms) y
    // del crossfadeRunnable (cada 100ms), para detectar jank del hilo
    // principal.

    companion object {
        // Cada cuanto se revisa el progreso y se recalcula el volumen del
        // crossfade. 100ms da un fundido suave sin gastar mucha CPU.
        private const val FADE_STEP_MS = 100L

        // Paso mas fino para los fundidos cortos (pausa/play y cambio
        // manual de cancion): con duraciones de ~180-220ms, un paso de
        // 100ms daria solo 2 escalones y se notaria "a saltos".
        private const val FAST_FADE_STEP_MS = 20L
        private const val PAUSE_PLAY_FADE_MS = 220L
        private const val MANUAL_CHANGE_FADE_MS = 200L

        private const val CROSSFADE_PREPARE_LEAD_MS = 4000L

        private const val TAG_XFADE = "MP3_XFADE"

        // AudioAttributes explicitos para todos los ExoPlayer de musica.
        private val MUSIC_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }

    // Listener de "cancion actual termino". Es el mismo objeto para todos
    // los players "principales" que van pasando por mediaPlayer; se
    // agrega/quita segun haga falta (equivalente a los antiguos
    // setOnCompletionListener(...) / setOnCompletionListener(null)).
    private val mainPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                callback.onTrackEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG_XFADE, "onPlayerError en player principal: ${error.message}")
        }
    }

    fun getAudioSessionId(): Int = sharedAudioSessionId

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition?.toInt() ?: 0

    fun getDuration(): Int {
        val duration = mediaPlayer?.duration ?: return 0
        return if (duration == C.TIME_UNSET) 0 else duration.toInt()
    }

    /** Duracion cruda tal cual la reporta ExoPlayer (puede ser C.TIME_UNSET), para PlaybackNotifier. */
    fun currentExoDurationMs(): Long? = mediaPlayer?.duration

    fun playSongAt(index: Int) {
        val song = callback.songAt(index) ?: return

        // Si ya habia algo sonando, no se libera de inmediato: se deja
        // sonando un instante mas mientras se apaga con un fundido corto
        // (manualFadeOutAndRelease), al mismo tiempo que la cancion nueva
        // arranca en silencio y sube (manualFadeIn). Asi el cambio manual
        // de cancion (siguiente/anterior/tocar una de la lista) tambien
        // suena a fundido y no a corte seco, igual que el crossfade
        // automatico de fin de cancion.
        val outgoingPlayer = mediaPlayer
        val hadOutgoing = outgoingPlayer != null && outgoingPlayer.isPlaying

        progressRunnable?.let { handler.removeCallbacks(it) }
        cancelCrossfadeIfAny()
        cancelManualChangeFadesIfAny()
        cancelPausePlayFadesIfAny()

        if (hadOutgoing && outgoingPlayer != null) {
            outgoingPlayer.removeListener(mainPlayerListener)
        } else {
            outgoingPlayer?.let {
                it.removeListener(mainPlayerListener)
                it.release()
            }
        }
        mediaPlayer = null

        val player = buildPlayer(startVolume = if (hadOutgoing) 0f else 1f)
        player.addListener(mainPlayerListener)
        attachAudioDiagnostics(player, "MAIN idx=$index")
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.playWhenReady = true
        mediaPlayer = player
        loadedIndex = index

        if (hadOutgoing && outgoingPlayer != null) {
            manualFadeOutAndRelease(outgoingPlayer)
            manualFadeIn(player)
        }

        startProgressUpdates()
        callback.onSongStarted(song, index, SongStartReason.NEW)
    }

    /** Reconstruye la ultima cancion reproducida sin arrancarla en automatico, en la posicion en que se habia quedado. */
    fun restoreSongAt(index: Int, positionMs: Long) {
        val song = callback.songAt(index) ?: return

        releasePlayer()

        val player = buildPlayer(startVolume = 1f)
        player.addListener(mainPlayerListener)
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.playWhenReady = false
        if (positionMs > 0) {
            player.seekTo(positionMs)
        }
        mediaPlayer = player
        loadedIndex = index

        startProgressUpdates()
        callback.onSongStarted(song, index, SongStartReason.RESTORED)
    }

    fun seekTo(positionMs: Int) {
        cancelCrossfadeIfAny()
        mediaPlayer?.seekTo(positionMs.toLong())
    }

    fun play() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) {
            cancelPausePlayFadesIfAny()
            player.playWhenReady = true
            fadeInOnPlay(player)
        }
        nextMediaPlayer?.let { if (!it.isPlaying) it.playWhenReady = true }
        callback.onPlaybackStateChanged(true)
    }

    fun pause() {
        // Pausar a la mitad de un crossfade se complica (dos players, dos
        // volumenes a medio camino) asi que mejor se cancela el efecto y
        // se sigue con la cancion que ya era la "principal".
        cancelCrossfadeIfAny()
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            cancelPausePlayFadesIfAny()
            fadeOutThenPause(player)
        }
        callback.onPlaybackStateChanged(false)
    }

    fun releasePlayer() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        cancelCrossfadeIfAny()
        cancelPausePlayFadesIfAny()
        cancelManualChangeFadesIfAny()
        mediaPlayer?.let {
            it.removeListener(mainPlayerListener)
            it.release()
        }
        mediaPlayer = null
    }

    // DEBUG: engancha diagnostico profundo de audio a un player. Esto NO
    // cambia comportamiento, solo agrega logs. El objetivo es distinguir
    // entre 3 causas MUY distintas para el mismo sintoma ("se escucha
    // menos y luego vuelve a lo normal"):
    //
    //  1) Nuestra propia matematica de volumen esta mal          -> ya
    //     deberia estar descartado con el fundido equal-power.
    //  2) El AudioTrack real sufre un UNDERRUN (se queda sin datos
    //     un instante) durante el cruce                          -> se ve
    //     como "onAudioUnderrun" en el log.
    //  3) El sistema (MIUI/HyperOS/Dolby/etc) esta RECONFIGURANDO la
    //     cadena de audio (por ejemplo al pasar de 1 a 2 AudioTrack
    //     activos, o algun efecto global) cuando aparece el segundo
    //     player                                                  -> se ve
    //     como un "onAudioTrackInitialized"/"onAudioTrackReleased"
    //     inesperado justo durante isCrossfading=true, o como un salto de
    //     "streamVol"/"onVolumeChanged" que NO viene de nuestro propio
    //     runCatching { ...volume = ... }.
    //
    // Filtra en Logcat con:  adb logcat -s MP3_XFADE
    @OptIn(UnstableApi::class)
    private fun attachAudioDiagnostics(player: ExoPlayer, label: String) {
        val playerId = System.identityHashCode(player)

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                Log.e(TAG_XFADE, "[$label #$playerId] onAudioSinkError: ${audioSinkError.message}", audioSinkError)
            }

            override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
                Log.e(TAG_XFADE, "[$label #$playerId] onAudioCodecError: ${audioCodecError.message}", audioCodecError)
            }
        })
    }

    // Crea un ExoPlayer con audio offload DESACTIVADO explicitamente. Esto es
    // lo que evita el corte: sin esto, en cuanto solo hay un AudioTrack
    // activo el sistema (MIUI/Dolby) lo pone en modo offload, y al abrir el
    // segundo player para el crossfade se ve forzado a sacarlo de offload y
    // reconfigurar la cadena de efectos -eso es el corte audible-.
    // Con offload desactivado desde el inicio, esa reconfiguracion nunca
    // ocurre.
    private fun buildPlayer(startVolume: Float): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                        .build()
                )
                .build()
        )

        return ExoPlayer.Builder(context, EqAudioSinkRenderersFactory(context))
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                // handleAudioFocus = false: mismo comportamiento manual que
                // tenia MediaPlayer (la app nunca gestiono audio focus).
                setAudioAttributes(MUSIC_AUDIO_ATTRIBUTES, false)
                volume = startVolume

                if (sharedAudioSessionId != AudioManager.ERROR) {
                    // Ya existe una sesion real (adoptada de un player
                    // anterior): se la asignamos a este player nuevo para
                    // que el Equalizer siga aplicando sin importar cual
                    // player interno este sonando (cancion normal,
                    // restore o el segundo player del crossfade).
                    setAudioSessionId(sharedAudioSessionId)
                } else {
                    // Primer player del servicio: NO forzamos ninguna
                    // sesion inventada. Dejamos que ExoPlayer/AudioTrack
                    // genere su propia sesion nativa y la adoptamos como
                    // sharedAudioSessionId para el resto de la vida del
                    // servicio (ver comentario grande junto al campo).
                    val ownSessionId = this.audioSessionId
                    if (ownSessionId != AudioManager.ERROR && ownSessionId != 0) {
                        sharedAudioSessionId = ownSessionId
                    }
                }
            }
    }

    private fun startProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }

        val runnable = object : Runnable {
            override fun run() {
                val player = mediaPlayer
                if (player != null) {
                    val current = player.currentPosition
                    val total = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
                    callback.onProgress(current.toInt(), total.toInt())
                    // Solo evaluar crossfade si realmente esta sonando: si
                    // esta en pausa, current/total quedan congelados dentro
                    // de la ventana de crossfade y este tick se repetiria
                    // cada 500ms disparando el arranque del siguiente player
                    // una y otra vez, "reanudando" la musica aunque el
                    // usuario haya pausado.
                    if (player.isPlaying) {
                        handleCrossfadeTick(current, total)
                    }
                    handler.postDelayed(this, 500)
                }
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    // --- Logica de crossfade ---
    //
    // FASE 1 (con varios segundos de anticipacion): se prepara la siguiente
    // cancion en segundo plano con prepare() (ExoPlayer siempre es
    // asincrono, no bloquea el hilo principal). Se deja con playWhenReady =
    // true y volumen 0 desde el inicio, asi que en cuanto termina de
    // bufferear ya esta sonando en silencio -sin necesidad de un paso
    // adicional tipo "keepPlayingSilently"-.
    //
    // FASE 2 (cuando realmente toca cruzar): el segundo reproductor ya esta
    // sonando en silencio, asi que aqui no hace falta arrancarlo: solo se
    // inicia el fundido de volumen (subir uno, bajar el otro).
    private fun handleCrossfadeTick(currentMs: Long, totalMs: Long) {
        if (isCrossfading) return

        if (callback.isBlockedBySleepTimerEndOfSong()) {
            discardPreparedNextIfAny()
            return
        }

        if (callback.isRepeatOneMode() ||
            !SettingsRepository.isCrossfadeEnabled(context) ||
            callback.queueSize() < 2
        ) {
            discardPreparedNextIfAny()
            return
        }

        if (totalMs <= 0) return

        val fadeMs = SettingsRepository.getCrossfadeSeconds(context) * 1000L
        val remainingMs = totalMs - currentMs
        if (remainingMs <= 0) return

        val upcomingIndex = callback.nextIndexFrom(loadedIndex)
        if (upcomingIndex < 0) return

        // Fase 1: preparar con anticipacion, en segundo plano.
        if (remainingMs <= fadeMs + CROSSFADE_PREPARE_LEAD_MS &&
            preparedNextPlayer == null &&
            prepareRequestedForIndex != upcomingIndex
        ) {
            prepareNextPlayerAsync(upcomingIndex)
        }

        // Fase 2: si ya toca cruzar y el reproductor esta listo, arranca ya.
        if (remainingMs <= fadeMs) {
            val ready = preparedNextPlayer
            if (ready != null && preparedNextIndex == upcomingIndex) {
                beginCrossfade(upcomingIndex, ready, remainingMs.coerceAtMost(fadeMs))
            }
            // Si aun no esta listo (cancion muy corta, almacenamiento lento,
            // etc.) no se fuerza nada: se deja que termine normal y el
            // onTrackEnded existente hace el salto sin crossfade esa vez.
        }
    }

    private fun prepareNextPlayerAsync(index: Int) {
        val song = callback.songAt(index) ?: return
        prepareRequestedForIndex = index

        val player = buildPlayer(startVolume = 0f)
        player.addListener(createNextPlayerListener(index, player))
        attachAudioDiagnostics(player, "NEXT idx=$index")
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        // playWhenReady = true desde ya: en cuanto termine de bufferear
        // arranca solo, sonando en silencio (volume = 0 puesto en
        // buildPlayer), sin bloquear el hilo principal.
        player.playWhenReady = true
    }

    // Listener de preparado/error para el player que se esta precargando
    // para el crossfade. Equivalente a los antiguos
    // setOnPreparedListener/setOnErrorListener de MediaPlayer.
    private fun createNextPlayerListener(index: Int, player: ExoPlayer): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    if (prepareRequestedForIndex == index && preparedNextIndex != index) {
                        preparedNextPlayer = player
                        preparedNextIndex = index
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG_XFADE, "onError preparando indice=$index: ${error.message}")
                if (prepareRequestedForIndex == index) {
                    prepareRequestedForIndex = -1
                }
                runCatching { player.release() }
            }
        }
    }

    private fun discardPreparedNextIfAny() {
        preparedNextPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        preparedNextPlayer = null
        preparedNextIndex = -1
        prepareRequestedForIndex = -1
    }

    private fun beginCrossfade(upcomingIndex: Int, readyPlayer: ExoPlayer, durationMs: Long) {
        val current = mediaPlayer
        if (current == null || durationMs <= 0) {
            discardPreparedNextIfAny()
            return
        }

        nextMediaPlayer = readyPlayer
        nextIndexDuringCrossfade = upcomingIndex
        preparedNextPlayer = null
        preparedNextIndex = -1
        prepareRequestedForIndex = -1

        isCrossfading = true
        crossfadeTotalMs = durationMs
        crossfadeElapsedMs = 0L

        // El listener de "cancion termino" ya no debe disparar el salto
        // automatico: el propio fundido es quien decide cuando cambiar.
        current.removeListener(mainPlayerListener)

        // El segundo player ya viene sonando en silencio desde que termino
        // de bufferear (playWhenReady=true puesto en prepareNextPlayerAsync).
        // Si por alguna razon llegara sin sonar (no deberia pasar nunca),
        // esto actua como red de seguridad.
        if (!readyPlayer.isPlaying) {
            Log.w(TAG_XFADE, "beginCrossfade(): readyPlayer NO estaba sonando, forzando playWhenReady=true de emergencia")
            readyPlayer.playWhenReady = true
        }

        crossfadeRunnable?.let { handler.removeCallbacks(it) }
        val crossfadeRunnableLocal = object : Runnable {
            override fun run() {
                if (!isCrossfading) return

                crossfadeElapsedMs += FADE_STEP_MS
                val fraction = (crossfadeElapsedMs.toFloat() / crossfadeTotalMs.toFloat()).coerceIn(0f, 1f)

                // Curva de potencia constante (equal-power) en vez de lineal:
                // con una rampa lineal (1-f / f), la suma de potencia percibida
                // (1-f)^2 + f^2 cae hasta la mitad justo en fraction=0.5 y
                // vuelve a subir en los extremos -eso es el "bajon y luego
                // sube" que se oye a la mitad del crossfade-. Con seno/coseno,
                // outgoingVolume^2 + incomingVolume^2 = 1 se mantiene
                // constante durante todo el cruce (identidad cos^2+sin^2=1).
                val angle = fraction * (Math.PI.toFloat() / 2f)
                val outgoingVolume = cos(angle)
                val incomingVolume = sin(angle)

                runCatching { mediaPlayer?.volume = outgoingVolume }
                runCatching { nextMediaPlayer?.volume = incomingVolume }

                if (fraction >= 1f) {
                    finishCrossfade()
                } else {
                    handler.postDelayed(this, FADE_STEP_MS)
                }
            }
        }
        crossfadeRunnable = crossfadeRunnableLocal
        handler.post(crossfadeRunnableLocal)
    }

    private fun finishCrossfade() {
        crossfadeRunnable?.let { handler.removeCallbacks(it) }
        crossfadeRunnable = null

        val finishedIndex = nextIndexDuringCrossfade
        val incomingPlayer = nextMediaPlayer
        if (finishedIndex < 0 || incomingPlayer == null) {
            isCrossfading = false
            return
        }

        mediaPlayer?.removeListener(mainPlayerListener)
        mediaPlayer?.release()

        val song = callback.songAt(finishedIndex)
        mediaPlayer = incomingPlayer.apply {
            volume = 1f
            addListener(mainPlayerListener)
        }
        loadedIndex = finishedIndex
        nextMediaPlayer = null
        nextIndexDuringCrossfade = -1
        isCrossfading = false

        if (song != null) {
            callback.onSongStarted(song, finishedIndex, SongStartReason.CROSSFADE)
        }
    }

    // --- Fundido corto de pausa/play ---
    // A diferencia del crossfade (que cruza DOS canciones), esto solo
    // sube/baja el volumen de la cancion actual al pausar/reanudar
    // manualmente, para evitar el "click" de volumen al 100% cortando
    // seco.
    private fun fadeOutThenPause(player: ExoPlayer) {
        val startVolume = player.volume.takeIf { it > 0f } ?: 1f
        var elapsed = 0L
        val runnable = object : Runnable {
            override fun run() {
                if (mediaPlayer !== player) return
                elapsed += FAST_FADE_STEP_MS
                val fraction = (elapsed.toFloat() / PAUSE_PLAY_FADE_MS.toFloat()).coerceIn(0f, 1f)
                runCatching { player.volume = startVolume * (1f - fraction) }
                if (fraction < 1f) {
                    handler.postDelayed(this, FAST_FADE_STEP_MS)
                } else {
                    player.playWhenReady = false
                    runCatching { player.volume = 1f }
                    if (pauseFadeRunnable === this) pauseFadeRunnable = null
                }
            }
        }
        pauseFadeRunnable = runnable
        handler.post(runnable)
    }

    private fun fadeInOnPlay(player: ExoPlayer) {
        runCatching { player.volume = 0f }
        var elapsed = 0L
        val runnable = object : Runnable {
            override fun run() {
                if (mediaPlayer !== player) return
                elapsed += FAST_FADE_STEP_MS
                val fraction = (elapsed.toFloat() / PAUSE_PLAY_FADE_MS.toFloat()).coerceIn(0f, 1f)
                runCatching { player.volume = fraction }
                if (fraction < 1f) {
                    handler.postDelayed(this, FAST_FADE_STEP_MS)
                } else if (playFadeRunnable === this) {
                    playFadeRunnable = null
                }
            }
        }
        playFadeRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelPausePlayFadesIfAny() {
        pauseFadeRunnable?.let { handler.removeCallbacks(it) }
        playFadeRunnable?.let { handler.removeCallbacks(it) }
        pauseFadeRunnable = null
        playFadeRunnable = null
    }

    // --- Fundido corto de cambio manual de cancion ---
    // Se usa desde playSongAt(): el player saliente se apaga y se libera,
    // el entrante arranca en silencio y sube, ambos en paralelo durante
    // MANUAL_CHANGE_FADE_MS. No es un crossfade "de verdad" (no hay
    // pre-buffering con antelacion como en el automatico de fin de
    // cancion), pero para archivos locales el prepare() es practicamente
    // instantaneo, asi que el resultado se escucha igual de suave.
    private fun manualFadeOutAndRelease(player: ExoPlayer) {
        var elapsed = 0L
        val runnable = object : Runnable {
            override fun run() {
                elapsed += FAST_FADE_STEP_MS
                val fraction = (elapsed.toFloat() / MANUAL_CHANGE_FADE_MS.toFloat()).coerceIn(0f, 1f)
                runCatching { player.volume = (1f - fraction) }
                if (fraction < 1f) {
                    handler.postDelayed(this, FAST_FADE_STEP_MS)
                } else {
                    runCatching { player.stop() }
                    runCatching { player.release() }
                    if (manualFadeOutRunnable === this) manualFadeOutRunnable = null
                }
            }
        }
        manualFadeOutRunnable = runnable
        handler.post(runnable)
    }

    private fun manualFadeIn(player: ExoPlayer) {
        runCatching { player.volume = 0f }
        var elapsed = 0L
        val runnable = object : Runnable {
            override fun run() {
                if (mediaPlayer !== player) return
                elapsed += FAST_FADE_STEP_MS
                val fraction = (elapsed.toFloat() / MANUAL_CHANGE_FADE_MS.toFloat()).coerceIn(0f, 1f)
                runCatching { player.volume = fraction }
                if (fraction < 1f) {
                    handler.postDelayed(this, FAST_FADE_STEP_MS)
                } else if (manualFadeInRunnable === this) {
                    manualFadeInRunnable = null
                }
            }
        }
        manualFadeInRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelManualChangeFadesIfAny() {
        manualFadeOutRunnable?.let { handler.removeCallbacks(it) }
        manualFadeInRunnable?.let { handler.removeCallbacks(it) }
        manualFadeOutRunnable = null
        manualFadeInRunnable = null
    }

    // Aborta un crossfade en curso (si lo hay) y deja "mediaPlayer" como la
    // unica pista sonando, a volumen normal. Se llama antes de cualquier
    // accion manual del usuario (siguiente, anterior, seek, cambiar modo...)
    // para que nunca se quede un segundo player fantasma sonando.
    fun cancelCrossfadeIfAny() {
        discardPreparedNextIfAny()

        if (!isCrossfading && nextMediaPlayer == null) return

        crossfadeRunnable?.let { handler.removeCallbacks(it) }
        crossfadeRunnable = null

        runCatching { mediaPlayer?.volume = 1f }
        mediaPlayer?.let {
            it.removeListener(mainPlayerListener)
            it.addListener(mainPlayerListener)
        }

        nextMediaPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        nextMediaPlayer = null
        nextIndexDuringCrossfade = -1
        isCrossfading = false
    }
}