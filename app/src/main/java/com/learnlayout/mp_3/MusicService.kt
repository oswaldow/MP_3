package com.learnlayout.mp_3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

class MusicService : Service() {

    private val binder = MusicBinder()

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat

    private var originalList: List<Song> = emptyList()
    private var songList: List<Song> = emptyList()
    private var currentIndex: Int = 0

    private var playbackMode: PlaybackMode = PlaybackMode.NORMAL

    private var listener: PlaybackListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    // --- Crossfade ---
    // Mientras se hace crossfade hay DOS MediaPlayer sonando a la vez:
    // "mediaPlayer" (la cancion que esta terminando) y "nextMediaPlayer"
    // (la cancion que ya empezo a sonar bajito y va subiendo de volumen).
    private var nextMediaPlayer: MediaPlayer? = null
    private var nextIndexDuringCrossfade: Int = -1
    private var isCrossfading: Boolean = false
    private var crossfadeRunnable: Runnable? = null
    private var crossfadeTotalMs: Long = 0L
    private var crossfadeElapsedMs: Long = 0L

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

    fun getCurrentSong(): Song? = songList.getOrNull(currentIndex)

    fun getSongList(): List<Song> = songList

    fun getCurrentIndex(): Int = currentIndex

    fun getPlaybackMode(): PlaybackMode = playbackMode

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

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    fun seekTo(positionMs: Int) {
        cancelCrossfadeIfAny()
        mediaPlayer?.seekTo(positionMs)
        updateMediaSessionState(isPlaying())
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) pause() else play()
    }

    fun play() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) {
            player.start()
        }
        nextMediaPlayer?.let { if (!it.isPlaying) it.start() }
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
            player.pause()
        }
        listener?.onPlaybackStateChanged(false)
        updateNotification()
        updateMediaSessionState(false)
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

    private fun playSongAt(index: Int) {
        val song = songList.getOrNull(index) ?: return

        releasePlayer()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, song.uri)
            prepare()
            setVolume(1f, 1f)
            start()
            setOnCompletionListener { onCurrentPlayerCompleted() }
        }

        // FIX pantalla de bloqueo: sin esta metadata (sobre todo la duracion),
        // el sistema no sabe cuanto dura la cancion y la barra de progreso de
        // la notificacion / lockscreen se queda congelada en 00:00.
        updateMediaMetadata(song)

        listener?.onSongChanged(song, index)
        PlayCountRepository.incrementPlayCount(applicationContext, song.id)
        listener?.onPlaybackStateChanged(true)
        updateMediaSessionState(true)

        startForeground(NOTIFICATION_ID, buildNotification(song, true))
        startProgressUpdates()
    }

    private fun onCurrentPlayerCompleted() {
        if (playbackMode == PlaybackMode.REPEAT_ONE) {
            playSongAt(currentIndex)
        } else {
            playNext()
        }
    }

    private fun startProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }

        progressRunnable = object : Runnable {
            override fun run() {
                val player = mediaPlayer
                if (player != null) {
                    val current = player.currentPosition
                    val total = player.duration
                    listener?.onProgressChanged(current, total)
                    handleCrossfadeTick(current, total)
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(progressRunnable!!)
    }

    // --- Logica de crossfade ---
    //
    // El crossfade se hace en DOS fases para que no se sienta ningun corte:
    //
    // FASE 1 (con varios segundos de anticipacion): se prepara la siguiente
    // cancion en segundo plano con prepareAsync(), que NO bloquea el hilo
    // principal. Esto es justo lo que causaba el tartamudeo antes: se usaba
    // prepare() (sincrono) exactamente en el momento del cruce, y ese
    // bloqueo del hilo principal se sentia como que la musica se detenia un
    // instante.
    //
    // FASE 2 (cuando realmente toca cruzar): como el reproductor ya esta
    // preparado de antemano, solo hace falta llamar a start(), que es
    // practicamente instantaneo, y arrancar el fundido de volumen.

    private val CROSSFADE_PREPARE_LEAD_MS = 4000L

    private var preparedNextPlayer: MediaPlayer? = null
    private var preparedNextIndex: Int = -1
    private var prepareRequestedForIndex: Int = -1

    private fun nextIndexFor(fromIndex: Int): Int {
        if (songList.isEmpty()) return -1
        return if (fromIndex + 1 >= songList.size) 0 else fromIndex + 1
    }

    private fun handleCrossfadeTick(currentMs: Int, totalMs: Int) {
        if (isCrossfading) return

        if (playbackMode == PlaybackMode.REPEAT_ONE ||
            !SettingsRepository.isCrossfadeEnabled(applicationContext) ||
            songList.size < 2
        ) {
            discardPreparedNextIfAny()
            return
        }

        if (totalMs <= 0) return

        val fadeMs = SettingsRepository.getCrossfadeSeconds(applicationContext) * 1000L
        val remainingMs = (totalMs - currentMs).toLong()
        if (remainingMs <= 0) return

        val upcomingIndex = nextIndexFor(currentIndex)
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
            // onCompletion existente hace el salto sin crossfade esa vez.
        }
    }

    private fun prepareNextPlayerAsync(index: Int) {
        val song = songList.getOrNull(index) ?: return
        prepareRequestedForIndex = index

        val player = MediaPlayer()
        val ok = runCatching {
            player.setDataSource(applicationContext, song.uri)
            player.setVolume(0f, 0f)
            player.setOnPreparedListener {
                // Si mientras se preparaba el usuario ya cambio de cancion a
                // mano (siguiente/anterior/etc), este player quedo obsoleto.
                if (prepareRequestedForIndex == index) {
                    warmUpAndHold(player)
                    preparedNextPlayer = player
                    preparedNextIndex = index
                } else {
                    runCatching { player.release() }
                }
            }
            player.setOnErrorListener { _, _, _ ->
                if (prepareRequestedForIndex == index) {
                    prepareRequestedForIndex = -1
                }
                true
            }
            player.prepareAsync()
        }.isSuccess

        if (!ok) {
            // Uri invalida, archivo borrado, etc. No pasa nada: simplemente
            // no habra crossfade para esta transicion.
            prepareRequestedForIndex = -1
            runCatching { player.release() }
        }
    }

    // "Precalienta" el reproductor: lo arranca y lo pausa casi al instante,
    // a volumen 0. Con esto, Android ya crea y abre el AudioTrack de este
    // segundo reproductor con varios segundos de anticipacion, en vez de
    // hacerlo justo en el momento del crossfade (que es lo que generaba el
    // corte perceptible: abrir un AudioTrack nuevo mientras el otro sigue
    // sonando obliga al sistema a reconfigurar el mezclador de audio).
    // Como el pausado ocurre casi inmediatamente despues del start, la
    // posicion de la cancion practicamente no avanza, asi que cuando el
    // crossfade real empiece se sigue escuchando desde el arranque.
    private fun warmUpAndHold(player: MediaPlayer) {
        runCatching {
            player.start()
            player.pause()
            player.seekTo(0)
        }
    }

    private fun discardPreparedNextIfAny() {
        preparedNextPlayer?.let {
            runCatching { it.setOnPreparedListener(null) }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        preparedNextPlayer = null
        preparedNextIndex = -1
        prepareRequestedForIndex = -1
    }

    private fun beginCrossfade(upcomingIndex: Int, readyPlayer: MediaPlayer, durationMs: Long) {
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
        current.setOnCompletionListener(null)

        // El AudioTrack ya se creo y se abrio durante el precalentamiento
        // (warmUpAndHold), asi que esto solo reanuda una pista ya lista:
        // no deberia generar ningun corte en la que sigue sonando.
        readyPlayer.start()

        crossfadeRunnable?.let { handler.removeCallbacks(it) }
        crossfadeRunnable = object : Runnable {
            override fun run() {
                if (!isCrossfading) return
                crossfadeElapsedMs += FADE_STEP_MS
                val fraction = (crossfadeElapsedMs.toFloat() / crossfadeTotalMs.toFloat()).coerceIn(0f, 1f)

                val outgoingVolume = 1f - fraction
                val incomingVolume = fraction

                runCatching { mediaPlayer?.setVolume(outgoingVolume, outgoingVolume) }
                runCatching { nextMediaPlayer?.setVolume(incomingVolume, incomingVolume) }

                if (fraction >= 1f) {
                    finishCrossfade()
                } else {
                    handler.postDelayed(this, FADE_STEP_MS)
                }
            }
        }
        handler.post(crossfadeRunnable!!)
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

        // Suelta la cancion vieja y "asciende" la nueva a ser la principal.
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.release()

        val song = songList.getOrNull(finishedIndex)
        mediaPlayer = incomingPlayer.apply {
            setVolume(1f, 1f)
            setOnCompletionListener { onCurrentPlayerCompleted() }
        }
        currentIndex = finishedIndex
        nextMediaPlayer = null
        nextIndexDuringCrossfade = -1
        isCrossfading = false

        if (song != null) {
            updateMediaMetadata(song)
            listener?.onSongChanged(song, finishedIndex)
            PlayCountRepository.incrementPlayCount(applicationContext, song.id)
            updateNotification()
        }
        updateMediaSessionState(true)
    }

    // Aborta un crossfade en curso (si lo hay) y deja "mediaPlayer" como la
    // unica pista sonando, a volumen normal. Se llama antes de cualquier
    // accion manual del usuario (siguiente, anterior, seek, cambiar modo...)
    // para que nunca se quede un segundo MediaPlayer fantasma sonando.
    private fun cancelCrossfadeIfAny() {
        discardPreparedNextIfAny()

        if (!isCrossfading && nextMediaPlayer == null) return

        crossfadeRunnable?.let { handler.removeCallbacks(it) }
        crossfadeRunnable = null

        runCatching { mediaPlayer?.setVolume(1f, 1f) }
        mediaPlayer?.setOnCompletionListener { onCurrentPlayerCompleted() }

        nextMediaPlayer?.let {
            runCatching { it.setOnCompletionListener(null) }
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
        val duration = mediaPlayer?.duration?.toLong()?.takeIf { it > 0 } ?: song.duration
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0L

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
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MP_3")
            .setContentText("Listo para reproducir")
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val song = getCurrentSong() ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(song, isPlaying()))
    }

    private fun buildNotification(song: Song, playing: Boolean): Notification {
        val playPauseIcon = if (playing) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val previousPendingIntent = servicePendingIntent(ACTION_PREVIOUS)
        val playPausePendingIntent = servicePendingIntent(ACTION_PLAY_PAUSE)
        val nextPendingIntent = servicePendingIntent(ACTION_NEXT)
        val deletePendingIntent = servicePendingIntent(ACTION_STOP)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setOngoing(false)
            .setDeleteIntent(deletePendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", previousPendingIntent)
            .addAction(playPauseIcon, "Reproducir/Pausar", playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Siguiente", nextPendingIntent)
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

    private fun stopPlaybackAndService() {
        releasePlayer()
        listener?.onPlaybackStateChanged(false)
        updateMediaSessionState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        cancelCrossfadeIfAny()
        mediaPlayer?.release()
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
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        mediaSession.isActive = false
        mediaSession.release()
    }
}