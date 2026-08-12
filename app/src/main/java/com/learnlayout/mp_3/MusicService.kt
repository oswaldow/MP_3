package com.learnlayout.mp_3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.util.UnstableApi
import kotlin.math.cos
import kotlin.math.sin

class MusicService : Service() {

    private val binder = MusicBinder()

    private var mediaPlayer: ExoPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat

    // Caratula de la cancion que esta sonando ahora mismo, usada tanto en
    // la MediaMetadata (pantalla de bloqueo / control multimedia del
    // sistema) como en el largeIcon de la notificacion. Se llena de forma
    // asincrona por AlbumArtRepository, asi que empieza en null y se
    // actualiza cuando la carga termina.
    private var currentAlbumArt: Bitmap? = null

    private var originalList: List<Song> = emptyList()
    private var songList: List<Song> = emptyList()
    private var currentIndex: Int = 0

    private var playbackMode: PlaybackMode = PlaybackMode.NORMAL

    private var listener: PlaybackListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    // Una sola sesion de audio para TODOS los ExoPlayer que crea este
    // servicio (normal, restore, crossfade). Ya no hace falta para el
    // ecualizador (que ahora es por software, ver
    // SoftwareEqualizerProcessor / EqAudioSinkRenderersFactory), pero se
    // mantiene por si en el futuro se necesita para otro efecto de
    // audio del sistema. Ver buildPlayer().
    private var sharedAudioSessionId: Int = AudioManager.ERROR

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

    // --- Sleep timer ---
    // Dos modos, mutuamente excluyentes:
    //  - Por minutos: sleepTimerRunnable programado con handler.postDelayed,
    //    pausa la musica cuando se cumple (ver setSleepTimerMinutes()).
    //  - "Fin de cancion": sleepTimerPauseAtSongEnd = true, no se programa
    //    nada; se revisa en onCurrentPlayerCompleted() y ademas se bloquea
    //    el crossfade en handleCrossfadeTick() para que la cancion termine
    //    de forma normal (sin empalmarse con la siguiente).
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerEndAtMillis: Long = 0L
    private var sleepTimerPauseAtSongEnd: Boolean = false

    enum class PlaybackMode { NORMAL, REPEAT_ONE, SHUFFLE }

    interface PlaybackListener {
        fun onSongChanged(song: Song, index: Int)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onProgressChanged(currentMs: Int, totalMs: Int)
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.learnlayout.mp_3.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.learnlayout.mp_3.action.NEXT"
        const val ACTION_PREVIOUS = "com.learnlayout.mp_3.action.PREVIOUS"
        const val ACTION_STOP = "com.learnlayout.mp_3.action.STOP"

        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1001

        // Cada cuanto se revisa el progreso y se recalcula el volumen del
        // crossfade. 100ms da un fundido suave sin gastar mucha CPU.
        private const val FADE_STEP_MS = 100L

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

    // Marca de tiempo del ultimo tick del progressRunnable (cada 500ms) y del
    // crossfadeRunnable (cada 100ms), para detectar jank del hilo principal.
    private var lastProgressTickNanos: Long = 0L
    private var lastFadeTickNanos: Long = 0L

    // Ultimo momento en que se guardo cancion+posicion en disco mientras
    // suena musica. Se usa para no escribir en SharedPreferences 2 veces
    // por segundo (cada tick de progreso), solo cada ~5s.
    private var lastPlaybackStateSaveNanos: Long = 0L

    // Listener de "cancion actual termino". Es el mismo objeto para todos
    // los players "principales" que van pasando por mediaPlayer; se
    // agrega/quita segun haga falta (equivalente a los antiguos
    // setOnCompletionListener(...) / setOnCompletionListener(null)).
    private val mainPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // DEBUG: si el player que sigue sonando (el "viejo") entra en
            // STATE_BUFFERING justo durante el crossfade, eso solo -sin
            // tocar para nada el volumen- ya suena como un bajon fuerte
            // seguido de una recuperacion cuando termina de bufferear.
            // Si ves este log durante un crossfade, el problema NO es el
            // fundido de volumen sino un stall de buffering.
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
            Log.d(TAG_XFADE, "mainPlayerListener.onPlaybackStateChanged -> $stateName (isCrossfading=$isCrossfading)")
            if (playbackState == Player.STATE_ENDED) {
                onCurrentPlayerCompleted()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG_XFADE, "onPlayerError en player principal: ${error.message}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // DEBUG: si esto se vuelve false durante isCrossfading=true sin
            // que nosotros lo hayamos pausado, es la causa del bajon.
            Log.d(TAG_XFADE, "mainPlayerListener.onIsPlayingChanged -> $isPlaying (isCrossfading=$isCrossfading)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "MusicServiceSession")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                play()
            }

            override fun onPause() {
                pause()
            }

            override fun onSkipToNext() {
                playNext()
            }

            override fun onSkipToPrevious() {
                playPrevious()
            }

            override fun onStop() {
                stopPlaybackAndService()
            }

            override fun onSeekTo(pos: Long) {
                seekTo(pos.toInt())
            }
        })
        // Hace que el control multimedia del sistema (pantalla de bloqueo,
        // banner de reproduccion, quick settings) abra la app al tocarlo.
        mediaSession.setSessionActivity(openAppPendingIntent())
        mediaSession.isActive = true
        updateMediaSessionState(false)

        // Android exige que un servicio arrancado con startForegroundService()
        // llame a startForeground() en los primeros segundos, sin importar si
        // ya hay una cancion sonando. Si no se hace de inmediato, el sistema
        // genera un ANR que puede terminar matando la app. Por eso se llama
        // aqui con una notificacion "idle" y luego se actualiza con los datos
        // reales de la cancion en playSongAt().
        startForeground(NOTIFICATION_ID, buildIdleNotification())
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_STOP -> stopPlaybackAndService()
        }
        return START_STICKY
    }

    fun setListener(listener: PlaybackListener?) {
        this.listener = listener
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int) {
        originalList = songs
        songList = songs
        currentIndex = startIndex
        playbackMode = PlaybackMode.NORMAL
        if (songList.isNotEmpty()) {
            playSongAt(currentIndex)
        }
    }

    // Reconstruye la ultima cancion reproducida sin contarla como una
    // reproduccion nueva ni arrancarla en automatico: la deja preparada y
    // en pausa, en la posicion en la que se habia quedado antes de que el
    // proceso muriera.
    fun restorePlaylist(songs: List<Song>, startIndex: Int, positionMs: Long) {
        if (songs.isEmpty()) return
        originalList = songs
        songList = songs
        currentIndex = startIndex.coerceIn(0, songs.size - 1)
        playbackMode = PlaybackMode.NORMAL
        restoreSongAt(currentIndex, positionMs)
    }

    private fun restoreSongAt(index: Int, positionMs: Long) {
        val song = songList.getOrNull(index) ?: return

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

        updateMediaMetadata(song)
        listener?.onSongChanged(song, index)
        listener?.onPlaybackStateChanged(false)
        updateMediaSessionState(false)

        startForeground(NOTIFICATION_ID, buildNotification(song, false))
        updateWidgets()
        startProgressUpdates()
    }

    fun getCurrentSong(): Song? = songList.getOrNull(currentIndex)

    fun getSongList(): List<Song> = songList

    fun getCurrentIndex(): Int = currentIndex

    fun getPlaybackMode(): PlaybackMode = playbackMode

    fun getAudioSessionId(): Int = sharedAudioSessionId

    fun cyclePlaybackMode(): PlaybackMode {
        val next = when (playbackMode) {
            PlaybackMode.NORMAL -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.SHUFFLE
            PlaybackMode.SHUFFLE -> PlaybackMode.NORMAL
        }
        setPlaybackMode(next)
        return next
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        cancelCrossfadeIfAny()
        val currentSong = getCurrentSong()
        playbackMode = mode

        if (originalList.isEmpty()) return

        when (mode) {
            PlaybackMode.SHUFFLE -> {
                val rest = originalList.filter { it.id != currentSong?.id }.shuffled()
                val newList = mutableListOf<Song>()
                if (currentSong != null) newList.add(currentSong)
                newList.addAll(rest)
                songList = newList
                currentIndex = 0
            }
            else -> {
                songList = originalList
                val foundIndex = originalList.indexOfFirst { it.id == currentSong?.id }
                currentIndex = if (foundIndex >= 0) foundIndex else 0
            }
        }
    }

    fun playAt(index: Int) {
        if (index in songList.indices) {
            cancelCrossfadeIfAny()
            currentIndex = index
            playSongAt(index)
        }
    }

    // Reacomoda la cola moviendo una cancion de fromIndex a toIndex.
    // Funciona igual en NORMAL, REPEAT_ONE y SHUFFLE. Fuera de SHUFFLE,
    // songList y originalList representan el mismo orden, asi que el
    // reacomodo se guarda como el nuevo orden base de la cola.
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in songList.indices || toIndex !in songList.indices) return

        cancelCrossfadeIfAny()

        val mutableList = songList.toMutableList()
        val movingSong = mutableList.removeAt(fromIndex)
        mutableList.add(toIndex, movingSong)
        songList = mutableList

        currentIndex = when {
            fromIndex == currentIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }

        if (playbackMode != PlaybackMode.SHUFFLE) {
            originalList = songList
        }
    }

    /**
     * Elimina una canción de la cola sin detener la reproducción actual.
     *
     * La canción que está sonando no se puede eliminar.
     *
     * La eliminación se mantiene también cuando estamos
     * en modo SHUFFLE, para que la canción no vuelva a aparecer
     * al cambiar posteriormente a reproducción normal.
     */
    fun removeQueueItem(index: Int): Boolean {

        if (index !in songList.indices) {
            return false
        }

        // No permitimos eliminar la canción que está sonando.
        if (index == currentIndex) {
            return false
        }

        if (songList.size <= 1) {
            return false
        }

        cancelCrossfadeIfAny()

        val songToRemove =
            songList[index]

        /*
         * Eliminar de la cola visible.
         */
        val newQueue =
            songList.toMutableList()

        newQueue.removeAt(index)

        songList =
            newQueue

        /*
         * Si eliminamos una canción que estaba
         * antes de la canción actual, el índice
         * actual debe retroceder una posición.
         */
        if (index < currentIndex) {
            currentIndex--
        }

        /*
         * También la eliminamos de originalList.
         *
         * Esto es especialmente importante en SHUFFLE:
         * si solamente elimináramos de songList,
         * la canción volvería cuando se desactive
         * el modo aleatorio.
         */
        originalList =
            originalList.filter {
                it.id != songToRemove.id
            }

        /*
         * Seguridad: si por alguna razón originalList
         * quedara vacía pero todavía existe una cola,
         * mantenemos la cola actual como base.
         */
        if (
            originalList.isEmpty() &&
            songList.isNotEmpty()
        ) {
            originalList =
                songList
        }

        return true
    }

    // Inserta una cancion justo despues de la actual, para que suene a
    // continuacion (como "Agregar a la cola" en Spotify). Si aun no hay
    // nada reproduciendose, simplemente arranca la reproduccion con ella.
    fun addToPlayNext(song: Song) {
        if (songList.isEmpty()) {
            setPlaylist(listOf(song), 0)
            return
        }

        cancelCrossfadeIfAny()

        val insertAt = currentIndex + 1
        val mutableList = songList.toMutableList()
        mutableList.add(insertAt, song)
        songList = mutableList

        if (playbackMode != PlaybackMode.SHUFFLE) {
            originalList = songList
        }
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition?.toInt() ?: 0

    fun getDuration(): Int {
        val duration = mediaPlayer?.duration ?: return 0
        return if (duration == C.TIME_UNSET) 0 else duration.toInt()
    }

    fun seekTo(positionMs: Int) {
        cancelCrossfadeIfAny()
        mediaPlayer?.seekTo(positionMs.toLong())
        updateMediaSessionState(isPlaying())
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) pause() else play()
    }

    fun play() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) {
            player.playWhenReady = true
        }
        nextMediaPlayer?.let { if (!it.isPlaying) it.playWhenReady = true }
        listener?.onPlaybackStateChanged(true)
        updateNotification()
        updateMediaSessionState(true)
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
        listener?.onPlaybackStateChanged(false)
        updateNotification()
        updateMediaSessionState(false)
        getCurrentSong()?.let {
            PlaybackStateRepository.saveLastSong(applicationContext, it.id, getCurrentPosition().toLong())
        }
    }

    // ---------- Sleep timer ----------

    /** Programa la pausa automatica dentro de [minutes] minutos. Cancela cualquier timer previo. */
    fun setSleepTimerMinutes(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return

        val delayMs = minutes * 60_000L
        sleepTimerEndAtMillis = System.currentTimeMillis() + delayMs

        val runnable = Runnable {
            sleepTimerRunnable = null
            sleepTimerEndAtMillis = 0L
            pause()
        }
        sleepTimerRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /** Pausa cuando termine la cancion que esta sonando en este momento (sin crossfade hacia la siguiente). */
    fun setSleepTimerEndOfSong() {
        cancelSleepTimer()
        sleepTimerPauseAtSongEnd = true
    }

    fun cancelSleepTimer() {
        sleepTimerRunnable?.let { handler.removeCallbacks(it) }
        sleepTimerRunnable = null
        sleepTimerEndAtMillis = 0L
        sleepTimerPauseAtSongEnd = false
    }

    fun isSleepTimerActive(): Boolean = sleepTimerRunnable != null || sleepTimerPauseAtSongEnd

    fun isSleepTimerEndOfSongActive(): Boolean = sleepTimerPauseAtSongEnd

    /** Milisegundos restantes del timer por minutos, o -1 si no hay uno activo (incluye el modo "fin de cancion"). */
    fun getSleepTimerRemainingMs(): Long {
        if (sleepTimerRunnable == null) return -1L
        return (sleepTimerEndAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun playNext() {
        if (songList.isEmpty()) return
        cancelCrossfadeIfAny()
        currentIndex = if (currentIndex + 1 >= songList.size) 0 else currentIndex + 1
        playSongAt(currentIndex)
    }

    fun playPrevious() {
        if (songList.isEmpty()) return
        cancelCrossfadeIfAny()
        currentIndex = if (currentIndex - 1 < 0) songList.size - 1 else currentIndex - 1
        playSongAt(currentIndex)
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
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun attachAudioDiagnostics(player: ExoPlayer, label: String) {
        val playerId = System.identityHashCode(player)

        player.addListener(object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                // Si esto se dispara con un valor que NO coincide con lo
                // que el crossfadeRunnable acaba de mandar, algo mas esta
                // tocando el volumen del player (por ejemplo otra parte
                // del codigo, o el propio framework).
                Log.w(
                    TAG_XFADE,
                    "[$label #$playerId] onVolumeChanged -> $volume (isCrossfading=$isCrossfading)"
                )
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
                Log.w(
                    TAG_XFADE,
                    "[$label #$playerId] onAudioTrackReleased (isCrossfading=$isCrossfading)"
                )
            }

            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception
            ) {
                Log.e(TAG_XFADE, "[$label #$playerId] onAudioSinkError: ${audioSinkError.message}", audioSinkError)
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception
            ) {
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
        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                        .build()
                )
                .build()
        )

        return ExoPlayer.Builder(this, EqAudioSinkRenderersFactory(this))
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

    private fun playSongAt(index: Int) {
        val song = songList.getOrNull(index) ?: return

        releasePlayer()

        val player = buildPlayer(startVolume = 1f)
        player.addListener(mainPlayerListener)
        attachAudioDiagnostics(player, "MAIN idx=$index")
        player.setMediaItem(MediaItem.fromUri(song.uri))
        player.prepare()
        player.playWhenReady = true
        mediaPlayer = player

        Log.d(TAG_XFADE, "playSongAt() indice=$index nuevo player=$player")

        // FIX pantalla de bloqueo: sin esta metadata (sobre todo la duracion),
        // el sistema no sabe cuanto dura la cancion y la barra de progreso de
        // la notificacion / lockscreen se queda congelada en 00:00.
        updateMediaMetadata(song)

        listener?.onSongChanged(song, index)
        PlayCountRepository.incrementPlayCount(applicationContext, song.id)
        PlaybackStateRepository.saveLastSong(applicationContext, song.id, 0L)
        listener?.onPlaybackStateChanged(true)
        updateMediaSessionState(true)

        startForeground(NOTIFICATION_ID, buildNotification(song, true))
        updateWidgets()
        startProgressUpdates()
    }

    private fun onCurrentPlayerCompleted() {
        if (sleepTimerPauseAtSongEnd) {
            sleepTimerPauseAtSongEnd = false
            listener?.onPlaybackStateChanged(false)
            updateNotification()
            updateMediaSessionState(false)
            getCurrentSong()?.let {
                PlaybackStateRepository.saveLastSong(applicationContext, it.id, getCurrentPosition().toLong())
            }
            return
        }
        if (playbackMode == PlaybackMode.REPEAT_ONE) {
            playSongAt(currentIndex)
        } else {
            playNext()
        }
    }

    private fun startProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }

        lastProgressTickNanos = 0L
        progressRunnable = object : Runnable {
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
                    listener?.onProgressChanged(current.toInt(), total.toInt())
                    // Solo evaluar crossfade si realmente esta sonando: si
                    // esta en pausa, current/total quedan congelados dentro
                    // de la ventana de crossfade y este tick se repetiria
                    // cada 500ms disparando el arranque del siguiente player
                    // una y otra vez, "reanudando" la musica aunque el
                    // usuario haya pausado.
                    if (player.isPlaying) {
                        handleCrossfadeTick(current, total)

                        if (now - lastPlaybackStateSaveNanos >= 5_000_000_000L) {
                            lastPlaybackStateSaveNanos = now
                            getCurrentSong()?.let { song ->
                                PlaybackStateRepository.saveLastSong(applicationContext, song.id, current)
                            }
                        }
                    }
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(progressRunnable!!)
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

    private val CROSSFADE_PREPARE_LEAD_MS = 4000L

    private var preparedNextPlayer: ExoPlayer? = null
    private var preparedNextIndex: Int = -1
    private var prepareRequestedForIndex: Int = -1

    private fun nextIndexFor(fromIndex: Int): Int {
        if (songList.isEmpty()) return -1
        return if (fromIndex + 1 >= songList.size) 0 else fromIndex + 1
    }

    private fun handleCrossfadeTick(currentMs: Long, totalMs: Long) {
        if (isCrossfading) return

        if (sleepTimerPauseAtSongEnd) {
            discardPreparedNextIfAny()
            return
        }

        if (playbackMode == PlaybackMode.REPEAT_ONE ||
            !SettingsRepository.isCrossfadeEnabled(applicationContext) ||
            songList.size < 2
        ) {
            discardPreparedNextIfAny()
            return
        }

        if (totalMs <= 0) return

        val fadeMs = SettingsRepository.getCrossfadeSeconds(applicationContext) * 1000L
        val remainingMs = totalMs - currentMs
        if (remainingMs <= 0) return

        val upcomingIndex = nextIndexFor(currentIndex)
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
            // onCompletion existente hace el salto sin crossfade esa vez.
        }
    }

    private fun prepareNextPlayerAsync(index: Int) {
        val song = songList.getOrNull(index) ?: return
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
                    // Si mientras se preparaba el usuario ya cambio de
                    // cancion a mano (siguiente/anterior/etc), este player
                    // quedo obsoleto.
                    if (prepareRequestedForIndex == index && preparedNextIndex != index) {
                        preparedNextPlayer = player
                        preparedNextIndex = index
                    }
                } else if (playbackState == Player.STATE_BUFFERING) {
                    // DEBUG: si el player ENTRANTE bufferea de nuevo despues
                    // de ya estar listo (por ejemplo, justo cuando arranca
                    // el fundido), tambien puede sonar como un bajon raro
                    // -distinto al fundido normal- aunque el volumen este
                    // bien calculado.
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

        // DEBUG: volumen fisico del stream ANTES de arrancar el fundido,
        // para comparar contra los "streamVol" que se van a loguear en
        // cada TICK. Si streamVol cambia durante el crossfade, hay algo
        // externo (tecla de volumen, otra app, el propio sistema) bajando
        // el volumen real del telefono, no nuestro fundido interno.
        val streamVolBefore = runCatching {
            (getSystemService(AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
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
        crossfadeRunnable = object : Runnable {
            override fun run() {
                if (!isCrossfading) return

                // DEBUG: cada tick deberia llegar cada ~100ms. Si llega mucho
                // mas tarde, el hueco de tiempo es exactamente lo que se
                // percibe como "la cancion se detiene un momento".
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

                // DEBUG: volumen que calculamos vs. el que ExoPlayer dice
                // tener aplicado de verdad (readback), y el volumen fisico
                // del stream de musica del telefono. Si "readback" no
                // coincide con lo que mandamos, ExoPlayer/el sistema esta
                // pisando el valor. Si el volumen del stream cambia solo
                // (streamVol), fue una tecla de volumen o algo del sistema,
                // no el fundido. Se loguea siempre (dura pocos segundos).
                val outReadback = runCatching { mediaPlayer?.volume }.getOrNull()
                val inReadback = runCatching { nextMediaPlayer?.volume }.getOrNull()
                val outState = runCatching { mediaPlayer?.playbackState }.getOrNull()
                val inState = runCatching { nextMediaPlayer?.playbackState }.getOrNull()
                // DEBUG: si outIsPlaying o inIsPlaying se vuelven false a
                // mitad del fundido sin que nosotros hayamos pausado nada,
                // es una senal fuerte de underrun/glitch del AudioTrack real
                // (ExoPlayer reporta isPlaying=false cuando el sink se queda
                // sin datos), aunque nuestro volumen calculado (outSet/inSet)
                // siga viendose "correcto" en el log.
                val outIsPlaying = runCatching { mediaPlayer?.isPlaying }.getOrNull()
                val inIsPlaying = runCatching { nextMediaPlayer?.isPlaying }.getOrNull()
                val streamVol = runCatching {
                    (getSystemService(AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
                }.getOrNull()
                // DEBUG: isMusicActive() consulta si el sistema considera que
                // HAY musica sonando ahora mismo (a nivel de mixer). Si esto
                // parpadea a false durante el cruce, algo por debajo de
                // ExoPlayer esta cortando el audio, no nuestro codigo.
                val musicActive = runCatching {
                    (getSystemService(AUDIO_SERVICE) as AudioManager).isMusicActive
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
        handler.post(crossfadeRunnable!!)

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

        // DEBUG: mide cada sub-paso. release() de un ExoPlayer puede
        // implicar esperar a que el decoder interno termine de liberar
        // recursos, y a veces NO es instantaneo.
        var t = System.nanoTime()
        mediaPlayer?.removeListener(mainPlayerListener)
        mediaPlayer?.release()
        logStep("mediaPlayer.release() (cancion vieja)", t)

        val song = songList.getOrNull(finishedIndex)
        mediaPlayer = incomingPlayer.apply {
            volume = 1f
            addListener(mainPlayerListener)
        }
        currentIndex = finishedIndex
        nextMediaPlayer = null
        nextIndexDuringCrossfade = -1
        isCrossfading = false

        if (song != null) {
            t = System.nanoTime()
            updateMediaMetadata(song)
            logStep("updateMediaMetadata()", t)

            t = System.nanoTime()
            listener?.onSongChanged(song, finishedIndex)
            logStep("listener.onSongChanged() (UI/Activity)", t)

            // DEBUG: esta escribe/lee SharedPreferences con JSON en el hilo
            // principal. Si la lista de conteos ya crecio bastante, parsear
            // y volver a serializar el JSON completo puede tardar mas de lo
            // que parece.
            t = System.nanoTime()
            PlayCountRepository.incrementPlayCount(applicationContext, song.id)
            logStep("PlayCountRepository.incrementPlayCount() (disco/SharedPreferences)", t)

            t = System.nanoTime()
            updateNotification()
            logStep("updateNotification()", t)
        }
        updateMediaSessionState(true)

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
    private fun cancelCrossfadeIfAny() {
        discardPreparedNextIfAny()

        if (!isCrossfading && nextMediaPlayer == null) return

        // DEBUG: si esto se dispara MIENTRAS el fundido esta a medias
        // (isCrossfading=true), el volumen de la cancion actual salta de
        // golpe de vuelta a 1.0 -eso tambien se percibiria como un corte/
        // salto raro-. Interesa saber si algo esta llamando a alguna accion
        // manual (pausa, seek, siguiente...) justo durante el crossfade.
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

    /**
     * Publica en la MediaSession el titulo, artista y duracion de la cancion
     * actual. Es indispensable para que la pantalla de bloqueo y la
     * notificacion multimedia muestren y animen correctamente la barra de
     * progreso (Android calcula el avance a partir de esta duracion mas la
     * posicion/velocidad reportadas en el PlaybackState).
     */
    private fun updateMediaMetadata(song: Song) {
        val exoDuration = mediaPlayer?.duration
        val duration = if (exoDuration != null && exoDuration != C.TIME_UNSET && exoDuration > 0) {
            exoDuration
        } else {
            song.duration
        }

        fun buildMetadata(art: Bitmap?): MediaMetadataCompat {
            val builder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            if (art != null) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            }
            return builder.build()
        }

        // Publica de inmediato titulo/artista/duracion sin caratula (para
        // que el banner y la pantalla de bloqueo no se queden en blanco
        // mientras se busca la imagen), y la agrega en cuanto este lista.
        currentAlbumArt = null
        mediaSession.setMetadata(buildMetadata(null))

        AlbumArtRepository.loadCover(
            applicationContext,
            song,
            object : AlbumArtRepository.Callback {
                override fun onCoverReady(bitmap: Bitmap) {
                    // Evita pisar la metadata si el usuario ya cambio de
                    // cancion mientras esta portada terminaba de cargar.
                    if (getCurrentSong()?.id != song.id) return
                    currentAlbumArt = bitmap
                    mediaSession.setMetadata(buildMetadata(bitmap))
                    updateNotification()
                }
            },
            isStillNeeded = { getCurrentSong()?.id == song.id }
        )
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = mediaPlayer?.currentPosition ?: 0L

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, position, 1f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildIdleNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("MP_3")
            .setContentText("Listo para reproducir")
            .setColor(ContextCompat.getColor(this, R.color.purple_primary))
            .setColorized(true)
            .setOngoing(false)
            .setContentIntent(openAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val song = getCurrentSong() ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(song, isPlaying()))
        updateWidgets()
    }

    /**
     * Refresca el widget de pantalla de inicio (si el usuario agrego uno)
     * con la cancion y el estado de reproduccion actuales.
     */
    private fun updateWidgets() {
        MusicWidgetProvider.pushUpdate(applicationContext, getCurrentSong(), isPlaying())
    }

    private fun buildNotification(song: Song, playing: Boolean): Notification {
        val playPauseIcon = if (playing) {
            R.drawable.ic_pause
        } else {
            R.drawable.ic_play_arrow
        }

        val previousPendingIntent = servicePendingIntent(ACTION_PREVIOUS)
        val playPausePendingIntent = servicePendingIntent(ACTION_PLAY_PAUSE)
        val nextPendingIntent = servicePendingIntent(ACTION_NEXT)
        val deletePendingIntent = servicePendingIntent(ACTION_STOP)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon(currentAlbumArt)
            // Notificacion "colorizada": el sistema tine el fondo con este
            // color (mismo morado de marca de la app) en vez del gris
            // generico, tanto en la barra de notificaciones como en el
            // banner de la pantalla de bloqueo.
            .setColor(ContextCompat.getColor(this, R.color.purple_primary))
            .setColorized(true)
            .setOngoing(false)
            .setContentIntent(openAppPendingIntent())
            .setDeleteIntent(deletePendingIntent)
            .addAction(R.drawable.ic_skip_previous, "Anterior", previousPendingIntent)
            .addAction(playPauseIcon, "Reproducir/Pausar", playPausePendingIntent)
            .addAction(R.drawable.ic_skip_next, "Siguiente", nextPendingIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    /**
     * Intent para abrir la app al tocar el banner/notificacion de
     * reproduccion (pantalla de bloqueo, control multimedia del sistema,
     * quick settings). Usa FLAG_ACTIVITY_CLEAR_TOP para volver a la
     * instancia existente de SongListActivity en vez de crear una nueva.
     */
    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, SongListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun stopPlaybackAndService() {
        PlaybackStateRepository.clearLastSong(applicationContext)
        releasePlayer()
        listener?.onPlaybackStateChanged(false)
        updateMediaSessionState(false)
        MusicWidgetProvider.pushUpdate(applicationContext, getCurrentSong(), false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        cancelCrossfadeIfAny()
        mediaPlayer?.let {
            it.removeListener(mainPlayerListener)
            it.release()
        }
        mediaPlayer = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproduccion de musica",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Controles de reproduccion de MP3"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // A partir de aqui el sistema puede matar el proceso en cualquier
        // momento (sobre todo en fabricantes agresivos tipo MIUI/HyperOS),
        // sin llegar a llamar onDestroy(). Se escribe de forma bloqueante
        // para asegurar que la posicion quede en disco antes de que eso
        // pase.
        getCurrentSong()?.let {
            PlaybackStateRepository.saveLastSongBlocking(applicationContext, it.id, getCurrentPosition().toLong())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSleepTimer()
        getCurrentSong()?.let {
            PlaybackStateRepository.saveLastSongBlocking(applicationContext, it.id, getCurrentPosition().toLong())
        }
        releasePlayer()
        mediaSession.isActive = false
        mediaSession.release()
    }
}