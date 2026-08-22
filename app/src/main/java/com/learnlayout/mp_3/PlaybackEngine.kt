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

    // Indice, dentro de la cola, de la cancion que "mediaPlayer" tiene
    // cargada ahora mismo. IMPORTANTE: esto es una copia local del
    // indice, no una referencia viva a QueueManager.getCurrentIndex().
    // Si la cola se reordena (arrastrar en la cola, swipe "sonara a
    // continuacion", quitar una cancion, cambiar a shuffle/normal...)
    // mientras esta cancion sigue sonando, QueueManager recalcula su
    // propio currentIndex para seguir apuntando a la MISMA cancion, pero
    // "loadedIndex" aqui NO se entera solo -hay que llamar a
    // syncLoadedIndex() explicitamente (ver mas abajo)-. Si no se
    // sincroniza, nextIndexFrom(loadedIndex) en handleCrossfadeTick()
    // calcula la "siguiente cancion" a precargar/crossfade usando la
    // posicion VIEJA, y termina preparando/cruzando hacia la cancion que
    // ahora vive en esa posicion vieja en vez de la que el usuario
    // realmente puso a continuacion.
    private var loadedIndex: Int = -1

    // Una sola sesion de audio para TODOS los ExoPlayer que crea este
    // motor (normal, restore, crossfade). Ya no hace falta para el
    // ecualizador (que ahora es por software, ver
    // SoftwareEqualizerProcessor / EqAudioSinkRenderersFactory), pero se
    // mantiene por si en el futuro se necesita para otro efecto de audio
    // del sistema. Ver buildPlayer().
    private var sharedAudioSessionId: Int = AudioManager.ERROR

    private var progressRunnable: Runnable? = null

    private var isSeeking: Boolean = false
    private var seekSafetyRunnable: Runnable? = null

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

    // Player que esta a mitad del fundido de salida disparado por
    // manualFadeOutAndRelease(), si lo hay. Se necesita como campo de la
    // clase (y no solo como variable capturada dentro del propio
    // Runnable) porque cancelManualChangeFadesIfAny() necesita poder
    // forzar su stop()+release() cuando ese Runnable se cancela A MEDIAS
    // (saltando de cancion muy rapido varias veces seguidas): si solo se
    // cancela el Runnable, el "else" que hacia stop()+release() nunca
    // llega a ejecutarse y ese reproductor se queda sonando para
    // siempre de fondo, superpuesto con las canciones siguientes. Eso es
    // lo que provocaba escuchar varias canciones a la vez al usar
    // "siguiente"/"anterior" repetidamente y rapido.
    private var manualFadeOutPlayer: ExoPlayer? = null

    private var duckLevel: Float = 1f
    private var duckRunnable: Runnable? = null

    private var preparedNextPlayer: ExoPlayer? = null
    private var preparedNextIndex: Int = -1

    // *** FIX: referencia al listener de "preparado" para poder quitarlo ***
    // createNextPlayerListener() se enganchaba a cada player candidato a
    // "siguiente" (prepareNextPlayerAsync) y JAMAS se le quitaba, ni al
    // promoverlo en beginCrossfade() ni al descartarlo en
    // discardPreparedNextIfAny(). Ese listener revisa, cada vez que el
    // player pasa a STATE_READY, si su "requestToken" sigue vigente; si no
    // (porque ya se pidio preparar otra cancion mientras tanto), hace
    // player.stop()+release().
    //
    // El problema: un ExoPlayer vuelve a pasar por STATE_READY no solo al
    // terminar de bufferear la primera vez, sino CADA VEZ que sale de un
    // STATE_BUFFERING -por ejemplo, al terminar un seekTo() del usuario-.
    // Como el listener nunca se despegaba, seguia vivo colgado del player
    // AUN DESPUES de que ese player se convirtiera en el "mediaPlayer"
    // principal via finishCrossfade(). Entonces: cronologia real del bug
    // -> termina el crossfade -> el motor empieza a preparar la SIGUIENTE
    // cancion (prepareRequestToken avanza) -> el usuario adelanta la
    // cancion actual con el dedo -> ExoPlayer pasa por BUFFERING y vuelve
    // a READY al terminar el seek -> el listener viejo se dispara, ve su
    // token obsoleto, y hace release() del reproductor que en ese momento
    // es la musica sonando en vivo. "mediaPlayer" se queda apuntando para
    // siempre a ese cadaver: play()/pausa()/seek() siguen "funcionando" en
    // apariencia pero sin sonido, y en logcat solo se ve, sin ningun
    // Log.d/e nuestro de por medio, una razaga de "ExoPlayerImplInternal:
    // Ignoring messages sent after release." cada vez que se le manda algo.
    //
    // Se guarda la referencia al listener exacto de cada preparacion para
    // poder desengancharlo con removeListener() en cuanto ese player deja
    // de ser "candidato a precargado", ya sea porque se promueve
    // (beginCrossfade) o porque se descarta (discardPreparedNextIfAny).
    private var preparedNextListener: Player.Listener? = null

    // ID de la cancion para la que se preparo/quedo listo preparedNextPlayer.
    // Se compara por ID (no solo por indice numerico de la cola) porque el
    // indice puede seguir siendo el mismo despues de que el usuario
    // reordene la cola a mano, aunque la CANCION que hay en ese indice ya
    // sea otra.
    private var preparedNextSongId: Long? = null
    private var prepareRequestedForSongId: Long? = null

    // Contador que se incrementa cada vez que se pide preparar un
    // siguiente player (o se descarta uno en curso). El listener
    // asincrono de preparado (createNextPlayerListener) solo acepta su
    // resultado si el token que capturo al arrancar sigue siendo el mas
    // reciente: asi, si mientras un player estaba bufferizando el
    // usuario reordeno la cola (lo que descarta/reinicia la preparacion),
    // el resultado tardio de ESE player viejo se ignora en vez de
    // instalarse como si fuera el correcto. Sin esto, era posible que
    // sonara la cancion que ANTES estaba en esa posicion de la cola en
    // vez de la que el usuario acababa de mover ahi.
    private var prepareRequestToken: Int = 0

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

        // Duracion del fundido de ducking y a que nivel queda la musica
        // mientras se lee un mensaje (25% del volumen normal).
        private const val DUCK_FADE_MS = 250L
        private const val DUCK_TARGET_LEVEL = 0.10f

        private const val CROSSFADE_PREPARE_LEAD_MS = 4000L

        // Tiempo maximo que se espera a que ExoPlayer confirme el fin de
        // un seek (onPositionDiscontinuity con DISCONTINUITY_REASON_SEEK)
        // antes de asumir por seguridad que ya termino. Es una red de
        // seguridad: si por lo que sea ese callback nunca llega, sin este
        // timeout "isSeeking" se quedaria en true para siempre y
        // cualquier fin de cancion legitimo posterior se ignoraria.
        private const val SEEK_SAFETY_TIMEOUT_MS = 1500L

        private const val TAG_XFADE = "MP3_XFADE"

        // AudioAttributes explicitos para todos los ExoPlayer de musica.
        private val MUSIC_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }

    private val mainPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                if (isSeeking) {
                    // Fin de pista recibido mientras un seek del usuario
                    // seguia en curso: es una notificacion espuria/tardia
                    // de ExoPlayer, no un fin de cancion real. Dejarla
                    // pasar es lo que provocaba liberar el player equivocado
                    // en playSongAt() (ver comentario en el campo isSeeking).
                    Log.w(TAG_XFADE, "onPlaybackStateChanged: STATE_ENDED ignorado por seek en curso")
                    return
                }
                callback.onTrackEnded()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                clearSeekState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Si el player entra en error a mitad de un seek, tambien hay
            // que soltar la bandera: de lo contrario un STATE_ENDED
            // legitimo posterior (de OTRA cancion, con OTRO player) podria
            // quedar bloqueado por error si por algun motivo isSeeking no
            // se limpiara en otro lado.
            clearSeekState()
            Log.e(TAG_XFADE, "onPlayerError en player principal: ${error.message}")
        }
    }

    /**
     * Corrige "loadedIndex" cuando la cola cambia de forma que la
     * cancion que sigue sonando pasa a vivir en OTRA posicion (reordenar,
     * quitar una cancion antes de la actual, cambiar de modo de
     * reproduccion...), sin recargar ni reiniciar nada en ExoPlayer: la
     * cancion sigue siendo exactamente la misma, solo cambia el numero de
     * indice con el que hay que ubicarla dentro de la cola.
     *
     * MusicService debe llamar a esto SIEMPRE despues de cualquier
     * operacion sobre QueueManager que pueda mover el currentIndex
     * mientras la reproduccion sigue con la misma cancion (moveQueueItem,
     * removeQueueItem, setPlaybackMode/cyclePlaybackMode). Si no se
     * llama, "loadedIndex" queda desfasado respecto al currentIndex real
     * de QueueManager y el crossfade puede terminar preparando/cruzando
     * hacia la cancion equivocada. Ver TAG_XFADE en logcat.
     */
    fun syncLoadedIndex(newIndex: Int) {
        if (newIndex < 0 || newIndex == loadedIndex) return

        Log.d(
            TAG_XFADE,
            "syncLoadedIndex: loadedIndex $loadedIndex -> $newIndex " +
                    "(cola reordenada/modificada). Se descarta cualquier " +
                    "'siguiente' precargado para el indice viejo."
        )

        loadedIndex = newIndex

        // Cualquier player que ya se hubiera empezado a preparar (o que
        // ya estuviera listo) para el "siguiente" calculado con el
        // indice VIEJO ya no sirve: hay que recalcular con el indice
        // correcto en el proximo tick de progreso.
        discardPreparedNextIfAny()
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

        // Cualquier seek pendiente del player saliente deja de importar:
        // estamos a punto de reemplazarlo (o liberarlo) de todas formas.
        clearSeekState()

        val outgoingPlayer = mediaPlayer
        val hadOutgoing = outgoingPlayer != null && outgoingPlayer.isPlaying

        progressRunnable?.let { handler.removeCallbacks(it) }
        cancelCrossfadeIfAny()
        cancelManualChangeFadesIfAny()
        cancelPausePlayFadesIfAny()

        Log.d(
            TAG_XFADE,
            "playSongAt(index=$index): outgoingPlayer=${outgoingPlayer?.let { System.identityHashCode(it) }} " +
                    "hadOutgoing=$hadOutgoing"
        )

        if (hadOutgoing && outgoingPlayer != null) {
            runCatching { outgoingPlayer.removeListener(mainPlayerListener) }
                .onFailure { Log.e(TAG_XFADE, "playSongAt: removeListener en outgoing fallo", it) }
        } else {
            outgoingPlayer?.let {
                runCatching { it.removeListener(mainPlayerListener) }
                    .onFailure { e -> Log.e(TAG_XFADE, "playSongAt: removeListener en outgoing fallo", e) }
                runCatching { it.release() }
                    .onFailure { e -> Log.e(TAG_XFADE, "playSongAt: release() del outgoing player fallo, " +
                            "se continua igual reasignando mediaPlayer", e) }
            }
        }
        mediaPlayer = null

        applyVolumeNormalization(song)

        val player = buildPlayer(startVolume = if (hadOutgoing) 0f else duckLevel)
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

        applyVolumeNormalization(song)

        val player = buildPlayer(startVolume = duckLevel)
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

    // Lee el ajuste de "Normalizar volumen" (SettingsActivity) y, si esta
    // activo, aplica de inmediato la ganancia cacheada de [song] (o 0 dB
    // mientras se analiza por primera vez). Ver SongGainRepository /
    // ReplayGainAudioProcessor.
    private fun applyVolumeNormalization(song: Song) {
        val enabled = SettingsRepository.isVolumeNormalizationEnabled(context)
        ReplayGainAudioProcessor.setEnabled(enabled)
        if (!enabled) return

        SongGainRepository.applyGainForSong(context, song) { gainDb ->
            // Si para cuando termino el analisis la cancion ya cambio, no
            // pisamos la ganancia de la que esta sonando ahora.
            if (mediaPlayer != null && loadedIndex >= 0 && callback.songAt(loadedIndex)?.id == song.id) {
                ReplayGainAudioProcessor.setCurrentGainDb(gainDb)
            }
        }
    }
    fun seekTo(positionMs: Int) {
        cancelCrossfadeIfAny()
        val player = mediaPlayer ?: return
        markSeekStarted()
        player.seekTo(positionMs.toLong())
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
        clearSeekState()
        cancelCrossfadeIfAny()
        cancelPausePlayFadesIfAny()
        cancelManualChangeFadesIfAny()

        mediaPlayer?.let {
            runCatching { it.removeListener(mainPlayerListener) }
                .onFailure { e -> Log.e(TAG_XFADE, "releasePlayer: removeListener fallo", e) }
            runCatching { it.release() }
                .onFailure { e -> Log.e(TAG_XFADE, "releasePlayer: release() fallo, se limpia mediaPlayer igual", e) }
        }
        mediaPlayer = null
    }

    private fun markSeekStarted() {
        isSeeking = true
        seekSafetyRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            isSeeking = false
            seekSafetyRunnable = null
        }
        seekSafetyRunnable = runnable
        handler.postDelayed(runnable, SEEK_SAFETY_TIMEOUT_MS)
    }

    private fun clearSeekState() {
        isSeeking = false
        seekSafetyRunnable?.let { handler.removeCallbacks(it) }
        seekSafetyRunnable = null
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
        // Se resuelve la CANCION (no solo el indice) que toca a
        // continuacion en este instante. Si el usuario reordeno la cola a
        // mano, esto ya refleja el cambio aunque el numero de indice sea
        // el mismo de antes.
        val upcomingSong = callback.songAt(upcomingIndex) ?: return

        // DEBUG: si esto se dispara, "loadedIndex" (interno de
        // PlaybackEngine) y el indice real de la cancion actual dentro de
        // la cola YA NO COINCIDEN. Es la causa exacta del bug de "se
        // reproduce la cancion equivocada tras reordenar la cola" -
        // buscar TAG_XFADE en logcat.
        if (remainingMs <= fadeMs + CROSSFADE_PREPARE_LEAD_MS) {
            Log.d(
                TAG_XFADE,
                "handleCrossfadeTick: loadedIndex=$loadedIndex upcomingIndex=$upcomingIndex " +
                        "upcomingSong='${upcomingSong.title}' (id=${upcomingSong.id}) remainingMs=$remainingMs " +
                        "isSeeking=$isSeeking"
            )
        }

        // Fase 1: preparar con anticipacion, en segundo plano. Se compara
        // por ID de cancion: si ya se pidio preparar esta MISMA cancion
        // para esta posicion no hace falta repetirlo, pero si la cancion
        // que hay ahora en upcomingIndex es DISTINTA de la que se pidio
        // preparar la ultima vez (por ejemplo, el usuario acaba de mover
        // otra cancion a esa posicion), se vuelve a pedir.
        if (remainingMs <= fadeMs + CROSSFADE_PREPARE_LEAD_MS &&
            preparedNextPlayer == null &&
            prepareRequestedForSongId != upcomingSong.id
        ) {
            prepareNextPlayerAsync(upcomingIndex, upcomingSong)
        }

        if (remainingMs <= fadeMs && !isSeeking) {
            val ready = preparedNextPlayer
            // Se verifica indice Y cancion: entre que el player quedo
            // listo y este momento, la cola pudo haberse reordenado. Sin
            // esta doble verificacion se podia terminar cruzando hacia la
            // cancion vieja que ya estaba precargada para ese indice.
            if (ready != null && preparedNextIndex == upcomingIndex && preparedNextSongId == upcomingSong.id) {
                beginCrossfade(upcomingIndex, ready, remainingMs.coerceAtMost(fadeMs))
            }
            // Si aun no esta listo (cancion muy corta, almacenamiento lento,
            // etc.) no se fuerza nada: se deja que termine normal y el
            // onTrackEnded existente hace el salto sin crossfade esa vez.
        }
    }

    private fun prepareNextPlayerAsync(index: Int, song: Song) {
        prepareRequestedForSongId = song.id
        // Cada solicitud de preparado se marca con un token propio y
        // creciente. Es lo que permite distinguir, cuando el listener de
        // abajo finalmente dispare STATE_READY, si sigue siendo la
        // solicitud vigente o si ya quedo obsoleta (cola reordenada,
        // cancion cambiada a mano, etc.) mientras bufferizaba.
        val requestToken = ++prepareRequestToken

        val player = buildPlayer(startVolume = 0f)
        val listener = createNextPlayerListener(index, song.id, requestToken, player)
        preparedNextListener = listener
        player.addListener(listener)
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
    private fun createNextPlayerListener(index: Int, songId: Long, requestToken: Int, player: ExoPlayer): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // Solo se acepta si "requestToken" sigue siendo el mas
                    // reciente (prepareRequestToken no avanzo desde que
                    // arranco esta preparacion). Si ya avanzo -porque se
                    // descarto esta preparacion o se pidio otra nueva para
                    // la misma posicion mientras tanto- este resultado
                    // llego tarde y se descarta en vez de instalarse como
                    // "el siguiente" a sonar.
                    if (requestToken == prepareRequestToken && preparedNextPlayer == null) {
                        preparedNextPlayer = player
                        preparedNextIndex = index
                        preparedNextSongId = songId
                    } else {
                        runCatching { player.stop() }
                        runCatching { player.release() }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG_XFADE, "onError preparando indice=$index: ${error.message}")
                if (requestToken == prepareRequestToken) {
                    prepareRequestedForSongId = null
                }
                runCatching { player.release() }
            }
        }
    }

    private fun discardPreparedNextIfAny() {
        // Invalida cualquier preparacion en curso, aunque todavia no haya
        // llegado a STATE_READY: al subir este contador, si esa
        // preparacion vieja termina de bufferear despues, su listener vera
        // que su token ya no es el vigente y se descartara sola.
        prepareRequestToken++

        preparedNextPlayer?.let { player ->
            preparedNextListener?.let { runCatching { player.removeListener(it) } }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        preparedNextPlayer = null
        preparedNextIndex = -1
        preparedNextSongId = null
        prepareRequestedForSongId = null
        preparedNextListener = null
    }

    private fun beginCrossfade(upcomingIndex: Int, readyPlayer: ExoPlayer, durationMs: Long) {
        val current = mediaPlayer
        if (current == null || durationMs <= 0) {
            discardPreparedNextIfAny()
            return
        }

        // *** FIX: desenganchar el listener de "preparado" ANTES de que ***
        // *** este player pase a vivir como nextMediaPlayer/mediaPlayer ***
        // Sin esto, createNextPlayerListener() se queda pegado para
        // siempre: la proxima vez que ExoPlayer vuelva a pasar por
        // STATE_READY (por ejemplo, al terminar un seekTo() del usuario ya
        // reproduciendo esta cancion como principal), ese listener viejo
        // ve su requestToken ya obsoleto y hace stop()+release() sobre el
        // reproductor que en ese momento es la musica sonando en vivo, sin
        // que "mediaPlayer" se entere ni se ponga en null. Ver comentario
        // largo en el campo "preparedNextListener".
        preparedNextListener?.let { runCatching { readyPlayer.removeListener(it) } }
        preparedNextListener = null

        nextMediaPlayer = readyPlayer
        nextIndexDuringCrossfade = upcomingIndex
        preparedNextPlayer = null
        preparedNextIndex = -1
        preparedNextSongId = null
        prepareRequestedForSongId = null

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
                val outgoingVolume = cos(angle) * duckLevel
                val incomingVolume = sin(angle) * duckLevel

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

        // El player entrante pasa a ser el principal: cualquier seek que
        // estuviera pendiente sobre el player SALIENTE (que esta a punto
        // de liberarse justo debajo) deja de tener sentido.
        clearSeekState()

        Log.d(
            TAG_XFADE,
            "finishCrossfade(): outgoing(mediaPlayer)=${mediaPlayer?.let { System.identityHashCode(it) }} " +
                    "-> incoming=${System.identityHashCode(incomingPlayer)} finishedIndex=$finishedIndex"
        )

        // *** FIX CRITICO ***
        // Antes, "mediaPlayer?.release()" iba SIN runCatching. Si lanzaba
        // una excepcion (estado interno raro de ExoPlayer, mas facil de
        // ver en MIUI que a veces se traga excepciones del hilo principal
        // sin mostrar el dialogo de crash), la funcion se cortaba EN ESTE
        // PUNTO: la reasignacion "mediaPlayer = incomingPlayer" de abajo
        // nunca llegaba a ejecutarse. Resultado: "mediaPlayer" se quedaba
        // apuntando para siempre al player SALIENTE (el que se estaba
        // intentando liberar, ya muerto o a medio morir), justo en el
        // instante en que el crossfade "termina" y arranca la cancion
        // nueva. Cualquier play()/pausa() posterior actuaba sobre ese
        // player muerto y no volvia a sonar nada (logcat: "Ignoring
        // messages sent after release."). Con el runCatching de abajo, la
        // excepcion se loguea pero la reasignacion a "incomingPlayer"
        // ocurre SIEMPRE.
        runCatching { mediaPlayer?.removeListener(mainPlayerListener) }
            .onFailure { e -> Log.e(TAG_XFADE, "finishCrossfade: removeListener del outgoing fallo", e) }
        runCatching { mediaPlayer?.release() }
            .onFailure { e -> Log.e(TAG_XFADE, "finishCrossfade: release() del outgoing fallo, " +
                    "se reasigna mediaPlayer al incoming igual", e) }

        val song = callback.songAt(finishedIndex)
        song?.let { applyVolumeNormalization(it) }

        mediaPlayer = incomingPlayer.apply {
            volume = duckLevel
            addListener(mainPlayerListener)
        }
        loadedIndex = finishedIndex
        nextMediaPlayer = null
        nextIndexDuringCrossfade = -1
        isCrossfading = false

        Log.d(TAG_XFADE, "finishCrossfade(): mediaPlayer ahora es ${System.identityHashCode(mediaPlayer)}")

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
        val startVolume = player.volume.takeIf { it > 0f } ?: duckLevel
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
                    runCatching { player.volume = duckLevel }
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
                runCatching { player.volume = fraction * duckLevel }
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
        manualFadeOutPlayer = player
        val startVolume = player.volume.takeIf { it > 0f } ?: duckLevel
        var elapsed = 0L
        val runnable = object : Runnable {
            override fun run() {
                elapsed += FAST_FADE_STEP_MS
                val fraction = (elapsed.toFloat() / MANUAL_CHANGE_FADE_MS.toFloat()).coerceIn(0f, 1f)
                runCatching { player.volume = startVolume * (1f - fraction) }
                if (fraction < 1f) {
                    handler.postDelayed(this, FAST_FADE_STEP_MS)
                } else {
                    runCatching { player.stop() }
                    runCatching { player.release() }
                    if (manualFadeOutRunnable === this) manualFadeOutRunnable = null
                    if (manualFadeOutPlayer === player) manualFadeOutPlayer = null
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
                runCatching { player.volume = fraction * duckLevel }
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

        // Si habia un player saliente a mitad de su fundido de apagado
        // (manualFadeOutAndRelease) y ese Runnable se acaba de cancelar
        // arriba, su rama "else" -la que hacia stop()+release()- ya NUNCA
        // se va a ejecutar sola. Sin este bloque, ese ExoPlayer se queda
        // vivo y sonando en segundo plano indefinidamente: es exactamente
        // lo que pasaba al tocar "siguiente"/"anterior" varias veces muy
        // rapido, donde se llegaban a acumular varios players sonando a
        // la vez (varias canciones traslapadas).
        manualFadeOutPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        manualFadeOutPlayer = null
    }

    // --- Ducking para lectura de mensajes de WhatsApp ---
    // Baja/sube duckLevel con el mismo patron Handler/Runnable que los
    // fundidos de arriba. No mueve el volumen de un player "a mano": lo
    // que cambia es el TECHO (duckLevel) contra el que ya apuntan todos
    // los demas fundidos, y ademas se aplica de inmediato al player
    // principal si no hay ningun otro fundido corriendo en ese momento
    // (ver applyDuckLevelToActivePlayer). Asi, si llega un mensaje justo
    // en medio de un pausa/play o un cambio manual de cancion, no hay dos
    // Runnables peleando por el mismo volumen: el ducking simplemente
    // pasa a ser el nuevo techo para el fundido que ya estaba corriendo.
    fun duckForSpeech() {
        animateDuckLevel(DUCK_TARGET_LEVEL)
    }

    fun unduckAfterSpeech() {
        animateDuckLevel(1f)
    }

    private fun animateDuckLevel(target: Float) {
        duckRunnable?.let { handler.removeCallbacks(it) }

        val startLevel = duckLevel
        if (startLevel == target) {
            applyDuckLevelToActivePlayer()
            return
        }

        var elapsed = 0L
        val runnable = object : Runnable {
            override fun run() {
                elapsed += FAST_FADE_STEP_MS
                val fraction = (elapsed.toFloat() / DUCK_FADE_MS.toFloat()).coerceIn(0f, 1f)
                duckLevel = startLevel + (target - startLevel) * fraction
                applyDuckLevelToActivePlayer()
                if (fraction < 1f) {
                    handler.postDelayed(this, FAST_FADE_STEP_MS)
                } else {
                    duckLevel = target
                    applyDuckLevelToActivePlayer()
                    if (duckRunnable === this) duckRunnable = null
                }
            }
        }
        duckRunnable = runnable
        handler.post(runnable)
    }

    // Aplica duckLevel de inmediato al audio que suena ahora, pero solo si
    // ningun otro fundido lo va a pisar en su proximo tick: si hay un
    // pausa/play, un cambio manual o un crossfade en curso, esos fundidos
    // ya recalculan su volumen contra duckLevel en cada paso (ver arriba),
    // asi que tocar el volumen aqui tambien seria una carrera entre dos
    // Runnables sobre el mismo ExoPlayer.
    private fun applyDuckLevelToActivePlayer() {
        if (pauseFadeRunnable != null || playFadeRunnable != null ||
            manualFadeOutRunnable != null || manualFadeInRunnable != null ||
            isCrossfading
        ) {
            return
        }
        runCatching { mediaPlayer?.volume = duckLevel }
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

        runCatching { mediaPlayer?.volume = duckLevel }
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