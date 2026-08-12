package com.learnlayout.mp_3

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlin.math.cos
import kotlin.math.sin

/**
 * Motor de reproduccion: crea y administra los ExoPlayer (el principal y
 * los del crossfade), el fundido cruzado entre canciones y el reporte
 * periodico de progreso.
 *
 * No sabe nada de notificaciones, MediaSession ni de la cola de
 * canciones: todo eso se lo pide a [Callback]. Extraido de MusicService,
 * que se habia vuelto gigante por concentrar toda esta logica de audio.
 */
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

    private var preparedNextPlayer: ExoPlayer? = null
    private var preparedNextIndex: Int = -1
    private var prepareRequestedForIndex: Int = -1

    // Marca de tiempo del ultimo tick del progressRunnable (cada 500ms) y
    // del crossfadeRunnable (cada 100ms), para detectar jank del hilo
    // principal.
    private var lastProgressTickNanos: Long = 0L
    private var lastFadeTickNanos: Long = 0L

    companion object {
        // Cada cuanto se revisa el progreso y se recalcula el volumen del
        // crossfade. 100ms da un fundido suave sin gastar mucha CPU.
        private const val FADE_STEP_MS = 100L

        private const val CROSSFADE_PREPARE_LEAD_MS = 4000L

        // --- DEBUG: instrumentacion temporal para hallar el origen del corte ---
        // Todo lo etiquetado con TAG_XFADE se puede filtrar en Logcat con:
        //   adb logcat -s MP3_XFADE
        private const val TAG_XFADE = "MP3_XFADE"

        // --- DEBUG: instrumentacion temporal para el ecualizador por software ---
        // Filtrar en Logcat con:
        //   adb logcat -s MP3_EQ
        private const val TAG_EQ = "MP3_EQ"

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
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
            Log.d(TAG_XFADE, "mainPlayerListener.onPlaybackStateChanged -> $stateName (isCrossfading=$isCrossfading)")
            if (playbackState == Player.STATE_ENDED) {
                callback.onTrackEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG_XFADE, "onPlayerError en player principal: ${error.message}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG_XFADE, "mainPlayerListener.onIsPlayingChanged -> $isPlaying (isCrossfading=$isCrossfading)")
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

        releasePlayer()

        val player = buildPlayer(startVolume = 1f)
        player.addListener(mainPlayerListener)
        attachAudioDiagnostics(player, "MAIN idx=$index")
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.playWhenReady = true
        mediaPlayer = player
        loadedIndex = index

        Log.d(TAG_XFADE, "playSongAt() indice=$index nuevo player=$player")

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
            player.playWhenReady = true
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
            player.playWhenReady = false
        }
        callback.onPlaybackStateChanged(false)
    }

    fun releasePlayer() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        cancelCrossfadeIfAny()
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

        player.addListener(object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                Log.w(TAG_XFADE, "[$label #$playerId] onVolumeChanged -> $volume (isCrossfading=$isCrossfading)")
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.w(
                    TAG_XFADE,
                    "[$label #$playerId] onAudioSessionIdChanged -> $audioSessionId (isCrossfading=$isCrossfading) <-- si pasa durante el cruce, la sesion de audio se esta reasignando"
                )
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.d(
                    TAG_XFADE,
                    "[$label #$playerId] onPlayWhenReadyChanged -> $playWhenReady reason=$reason (isCrossfading=$isCrossfading)"
                )
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                Log.w(
                    TAG_XFADE,
                    "[$label #$playerId] *** onAudioUnderrun *** bufferSizeMs=$bufferSizeMs " +
                            "elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs (isCrossfading=$isCrossfading) " +
                            "<-- CANDIDATO PRINCIPAL: el hilo de audio se quedo sin datos un instante, " +
                            "eso se oye exactamente como 'bajon y vuelve'"
                )
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig
            ) {
                Log.w(
                    TAG_XFADE,
                    "[$label #$playerId] onAudioTrackInitialized encoding=${audioTrackConfig.encoding} " +
                            "sampleRate=${audioTrackConfig.sampleRate} channelConfig=${audioTrackConfig.channelConfig} " +
                            "bufferSize=${audioTrackConfig.bufferSize} offload=${audioTrackConfig.offload} " +
                            "tunneling=${audioTrackConfig.tunneling} (isCrossfading=$isCrossfading) " +
                            "<-- si esto aparece DURANTE el cruce (no solo al arrancar el player), " +
                            "el sistema esta reconstruyendo el AudioTrack a mitad del fundido"
                )
            }

            override fun onAudioTrackReleased(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig
            ) {
                Log.w(TAG_XFADE, "[$label #$playerId] onAudioTrackReleased (isCrossfading=$isCrossfading)")
            }

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
                Log.d(TAG_EQ, "buildPlayer: player creado CON EqAudioSinkRenderersFactory (EQ inyectado)")
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
                    Log.d(TAG_XFADE, "buildPlayer: primer player, sesion propia de ExoPlayer=$ownSessionId")
                    if (ownSessionId != AudioManager.ERROR && ownSessionId != 0) {
                        sharedAudioSessionId = ownSessionId
                    }
                }
            }
    }

    private fun startProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }

        lastProgressTickNanos = 0L
        val runnable = object : Runnable {
            override fun run() {
                // DEBUG: si este tick tarda mucho mas de 500ms en llegar,
                // significa que el hilo principal estuvo bloqueado por algo
                // (GC, disco, binder IPC...) justo en ese hueco de tiempo.
                val now = System.nanoTime()
                if (lastProgressTickNanos != 0L) {
                    val deltaMs = (now - lastProgressTickNanos) / 1_000_000
                    if (deltaMs > 600) {
                        Log.w(TAG_XFADE, "JANK en hilo principal: tick de progreso tardo ${deltaMs}ms (esperado ~500ms). isCrossfading=$isCrossfading")
                    }
                }
                lastProgressTickNanos = now

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
            Log.d(TAG_XFADE, "FASE1 -> pidiendo preparar indice=$upcomingIndex remainingMs=$remainingMs fadeMs=$fadeMs")
            prepareNextPlayerAsync(upcomingIndex)
        }

        // Fase 2: si ya toca cruzar y el reproductor esta listo, arranca ya.
        if (remainingMs <= fadeMs) {
            val ready = preparedNextPlayer
            if (ready != null && preparedNextIndex == upcomingIndex) {
                Log.d(TAG_XFADE, "FASE2 -> arrancando beginCrossfade indice=$upcomingIndex remainingMs=$remainingMs")
                beginCrossfade(upcomingIndex, ready, remainingMs.coerceAtMost(fadeMs))
            } else {
                Log.w(TAG_XFADE, "FASE2 -> NO estaba listo el siguiente player (ready=${ready != null}, preparedNextIndex=$preparedNextIndex, upcomingIndex=$upcomingIndex). Se hara salto SIN crossfade.")
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

        Log.d(TAG_XFADE, "prepareNextPlayerAsync indice=$index (offload desactivado, sesion propia)")
    }

    // Listener de preparado/error para el player que se esta precargando
    // para el crossfade. Equivalente a los antiguos
    // setOnPreparedListener/setOnErrorListener de MediaPlayer.
    private fun createNextPlayerListener(index: Int, player: ExoPlayer): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    Log.d(TAG_XFADE, "onReady indice=$index (async, no deberia afectar al hilo principal)")
                    if (prepareRequestedForIndex == index && preparedNextIndex != index) {
                        preparedNextPlayer = player
                        preparedNextIndex = index
                    }
                } else if (playbackState == Player.STATE_BUFFERING) {
                    Log.d(TAG_XFADE, "player entrante indice=$index -> BUFFERING (isCrossfading=$isCrossfading)")
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
        val fnStart = System.nanoTime()
        val current = mediaPlayer
        if (current == null || durationMs <= 0) {
            discardPreparedNextIfAny()
            return
        }

        val readyIsPlayingBefore = runCatching { readyPlayer.isPlaying }.getOrDefault(false)
        val readyPosBefore = runCatching { readyPlayer.currentPosition }.getOrDefault(-1L)
        val currentPos = runCatching { current.currentPosition }.getOrDefault(-1L)
        Log.d(TAG_XFADE, "beginCrossfade() indice=$upcomingIndex durationMs=$durationMs | readyIsPlayingBefore=$readyIsPlayingBefore readyPosBefore=$readyPosBefore currentPos=$currentPos")

        val streamVolBefore = runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull()
        Log.d(TAG_XFADE, "beginCrossfade() streamVolAntes=$streamVolBefore")

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

        lastFadeTickNanos = 0L
        crossfadeRunnable?.let { handler.removeCallbacks(it) }
        val crossfadeRunnableLocal = object : Runnable {
            override fun run() {
                if (!isCrossfading) return

                val now = System.nanoTime()
                if (lastFadeTickNanos != 0L) {
                    val deltaMs = (now - lastFadeTickNanos) / 1_000_000
                    if (deltaMs > 150) {
                        Log.w(TAG_XFADE, "JANK durante crossfade: tick de fundido tardo ${deltaMs}ms (esperado ~100ms)")
                    }
                }
                lastFadeTickNanos = now

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

                val outReadback = runCatching { mediaPlayer?.volume }.getOrNull()
                val inReadback = runCatching { nextMediaPlayer?.volume }.getOrNull()
                val outState = runCatching { mediaPlayer?.playbackState }.getOrNull()
                val inState = runCatching { nextMediaPlayer?.playbackState }.getOrNull()
                val outIsPlaying = runCatching { mediaPlayer?.isPlaying }.getOrNull()
                val inIsPlaying = runCatching { nextMediaPlayer?.isPlaying }.getOrNull()
                val streamVol = runCatching {
                    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
                }.getOrNull()
                val musicActive = runCatching {
                    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
                }.getOrNull()
                Log.d(
                    TAG_XFADE,
                    "TICK fraction=${"%.2f".format(fraction)} " +
                            "outSet=${"%.2f".format(outgoingVolume)} outReadback=$outReadback outState=$outState outIsPlaying=$outIsPlaying " +
                            "inSet=${"%.2f".format(incomingVolume)} inReadback=$inReadback inState=$inState inIsPlaying=$inIsPlaying " +
                            "streamVol=$streamVol musicActive=$musicActive"
                )

                if (fraction >= 1f) {
                    finishCrossfade()
                } else {
                    handler.postDelayed(this, FADE_STEP_MS)
                }
            }
        }
        crossfadeRunnable = crossfadeRunnableLocal
        handler.post(crossfadeRunnableLocal)

        val fnMs = (System.nanoTime() - fnStart) / 1_000_000
        Log.d(TAG_XFADE, "beginCrossfade() function completa en ${fnMs}ms")
    }

    private fun finishCrossfade() {
        val fnStart = System.nanoTime()
        Log.d(TAG_XFADE, "finishCrossfade() INICIO")

        crossfadeRunnable?.let { handler.removeCallbacks(it) }
        crossfadeRunnable = null

        val finishedIndex = nextIndexDuringCrossfade
        val incomingPlayer = nextMediaPlayer
        if (finishedIndex < 0 || incomingPlayer == null) {
            isCrossfading = false
            return
        }

        var t = System.nanoTime()
        mediaPlayer?.removeListener(mainPlayerListener)
        mediaPlayer?.release()
        logStep("mediaPlayer.release() (cancion vieja)", t)

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
            t = System.nanoTime()
            callback.onSongStarted(song, finishedIndex, SongStartReason.CROSSFADE)
            logStep("callback.onSongStarted() (metadata/notificacion/UI)", t)
        }

        val fnMs = (System.nanoTime() - fnStart) / 1_000_000
        Log.d(TAG_XFADE, "finishCrossfade() FIN, total ${fnMs}ms")
    }

    // DEBUG: helper para loguear cuanto tardo un paso puntual, marcando en
    // rojo (Log.w) los que superen 30ms -suficiente para notarse como un
    // "salto" en el audio.
    private fun logStep(label: String, startNanos: Long) {
        val ms = (System.nanoTime() - startNanos) / 1_000_000
        if (ms > 30) {
            Log.w(TAG_XFADE, "$label tardo ${ms}ms")
        } else {
            Log.d(TAG_XFADE, "$label tardo ${ms}ms")
        }
    }

    // Aborta un crossfade en curso (si lo hay) y deja "mediaPlayer" como la
    // unica pista sonando, a volumen normal. Se llama antes de cualquier
    // accion manual del usuario (siguiente, anterior, seek, cambiar modo...)
    // para que nunca se quede un segundo player fantasma sonando.
    fun cancelCrossfadeIfAny() {
        discardPreparedNextIfAny()

        if (!isCrossfading && nextMediaPlayer == null) return

        if (isCrossfading) {
            Log.w(TAG_XFADE, "cancelCrossfadeIfAny() aborto un crossfade EN CURSO -> esto tambien puede sonar como un corte", Throwable("stacktrace de origen"))
        }

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