package com.learnlayout.mp_3

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper


class MusicService : Service() {

    private val binder = MusicBinder()
    private val handler = Handler(Looper.getMainLooper())

    private val queueManager = QueueManager()
    private var listener: PlaybackListener? = null

    private lateinit var playbackEngine: PlaybackEngine
    private lateinit var notifier: PlaybackNotifier
    private lateinit var sleepTimer: SleepTimerManager

    // Ultimo momento en que se guardo cancion+posicion en disco mientras
    // suena musica. Se usa para no escribir en SharedPreferences 2 veces
    // por segundo (cada tick de progreso), solo cada ~5s.
    private var lastPlaybackStateSaveNanos: Long = 0L

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
    }

    override fun onCreate() {
        super.onCreate()

        notifier = PlaybackNotifier(this, CHANNEL_ID, object : PlaybackNotifier.ActionCallback {
            override fun onPlayRequested() = play()
            override fun onPauseRequested() = pause()
            override fun onNextRequested() = playNext()
            override fun onPreviousRequested() = playPrevious()
            override fun onStopRequested() = stopPlaybackAndService()
            override fun onSeekRequested(positionMs: Long) = seekTo(positionMs.toInt())
        })
        notifier.createNotificationChannel()
        notifier.updatePlaybackState(false, 0L)

        playbackEngine = PlaybackEngine(this, handler, object : PlaybackEngine.Callback {
            override fun songAt(index: Int): Song? = queueManager.songAt(index)
            override fun nextIndexFrom(index: Int): Int = queueManager.nextIndexFrom(index)
            override fun queueSize(): Int = queueManager.size()
            override fun isRepeatOneMode(): Boolean = queueManager.getPlaybackMode() == PlaybackMode.REPEAT_ONE
            override fun isBlockedBySleepTimerEndOfSong(): Boolean = sleepTimer.isEndOfSongActive()

            override fun onSongStarted(song: Song, index: Int, reason: PlaybackEngine.SongStartReason) {
                handleSongStarted(song, index, reason)
            }

            override fun onProgress(currentMs: Int, totalMs: Int) {
                listener?.onProgressChanged(currentMs, totalMs)
                maybeSavePlaybackStateThrottled(currentMs.toLong())
            }

            override fun onPlaybackStateChanged(isPlaying: Boolean) {
                handlePlaybackStateChanged(isPlaying)
            }

            override fun onTrackEnded() {
                handleTrackEnded()
            }
        })

        sleepTimer = SleepTimerManager(handler) { pause() }

        // Android exige que un servicio arrancado con startForegroundService()
        // llame a startForeground() en los primeros segundos, sin importar si
        // ya hay una cancion sonando. Si no se hace de inmediato, el sistema
        // genera un ANR que puede terminar matando la app. Por eso se llama
        // aqui con una notificacion "idle" y luego se actualiza con los datos
        // reales de la cancion al arrancar una cancion.
        startForeground(NOTIFICATION_ID, notifier.buildIdleNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

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

    // ---------- Cola / playlist (delega en QueueManager) ----------

    fun setPlaylist(songs: List<Song>, startIndex: Int) {
        queueManager.setPlaylist(songs, startIndex)
        persistQueueState()
        if (songs.isNotEmpty()) {
            playbackEngine.playSongAt(startIndex)
        }
    }

    /**
     * Reemplaza la cola manteniendo la cancion que ya esta sonando.
     *
     * Se usa cuando el usuario toca desde Home una cancion que ya estaba
     * reproduciendose: queremos reconstruir la cola completa sin reiniciar
     * ExoPlayer ni perder la posicion actual.
     */
    fun replaceQueueKeepingCurrent(songs: List<Song>) {
        if (songs.isEmpty()) return

        val currentSong = getCurrentSong()
        val currentIndex = songs.indexOfFirst { it.id == currentSong?.id }

        if (currentIndex >= 0) {
            playbackEngine.cancelCrossfadeIfAny()
            queueManager.setPlaylist(songs, currentIndex)
            persistQueueState()
        } else {
            // Caso de seguridad: si por alguna razon la cancion actual ya no
            // existe en la biblioteca, no modificamos la reproduccion.
            return
        }
    }

    /**
     * Restaura la cola personalizada guardada en disco.
     *
     * Se llama desde SongListActivity una vez que la biblioteca local ya fue
     * cargada, para poder convertir los IDs persistidos en objetos Song.
     * Devuelve true si encontro una cola valida y pudo restaurarla.
     */
    fun restorePersistedQueue(songs: List<Song>): Boolean {
        if (queueManager.isEmpty() == false) return false
        if (songs.isEmpty()) return false

        val state = QueueStateRepository.get(applicationContext) ?: return false
        val songsById = songs.associateBy { it.id }

        val restoredQueue = state.queueIds.mapNotNull { songsById[it] }
        if (restoredQueue.isEmpty()) {
            QueueStateRepository.clear(applicationContext)
            return false
        }

        val restoredOriginal = state.originalQueueIds
            .mapNotNull { songsById[it] }
            .ifEmpty { restoredQueue }

        val currentIndex = when {
            state.currentSongId != -1L -> {
                val byId = restoredQueue.indexOfFirst { it.id == state.currentSongId }
                if (byId >= 0) byId else state.currentIndex.coerceIn(0, restoredQueue.lastIndex)
            }
            else -> state.currentIndex.coerceIn(0, restoredQueue.lastIndex)
        }

        queueManager.restorePersistedQueue(
            songs = restoredQueue,
            persistedOriginalList = restoredOriginal,
            startIndex = currentIndex,
            mode = state.playbackMode
        )

        persistQueueState()
        playbackEngine.restoreSongAt(
            queueManager.getCurrentIndex(),
            PlaybackStateRepository.getLastPositionMs(applicationContext)
        )
        return true
    }

    /** Reconstruye la ultima cancion reproducida sin contarla como reproduccion nueva ni arrancarla en automatico. */
    fun restorePlaylist(songs: List<Song>, startIndex: Int, positionMs: Long) {
        if (songs.isEmpty()) return
        queueManager.restorePlaylist(songs, startIndex)
        persistQueueState()
        playbackEngine.restoreSongAt(queueManager.getCurrentIndex(), positionMs)
    }

    fun getCurrentSong(): Song? = queueManager.getCurrentSong()

    fun getSongList(): List<Song> = queueManager.getSongList()

    fun getCurrentIndex(): Int = queueManager.getCurrentIndex()

    fun getPlaybackMode(): PlaybackMode = queueManager.getPlaybackMode()

    fun getAudioSessionId(): Int = playbackEngine.getAudioSessionId()

    fun cyclePlaybackMode(): PlaybackMode {
        playbackEngine.cancelCrossfadeIfAny()
        val mode = queueManager.cyclePlaybackMode()
        persistQueueState()
        return mode
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        playbackEngine.cancelCrossfadeIfAny()
        queueManager.setPlaybackMode(mode)
        persistQueueState()
    }

    fun playAt(index: Int) {
        if (index in queueManager.getSongList().indices) {
            playbackEngine.cancelCrossfadeIfAny()
            queueManager.setCurrentIndex(index)
            persistQueueState()
            playbackEngine.playSongAt(index)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playbackEngine.cancelCrossfadeIfAny()
        if (queueManager.moveQueueItem(fromIndex, toIndex)) {
            persistQueueState()
        }
    }

    fun removeQueueItem(index: Int): Boolean {
        if (index == queueManager.getCurrentIndex()) return false
        playbackEngine.cancelCrossfadeIfAny()
        val removed = queueManager.removeQueueItem(index)
        if (removed) persistQueueState()
        return removed
    }

    fun clearUpcomingQueue(): Int {
        playbackEngine.cancelCrossfadeIfAny()
        val removed = queueManager.clearUpcomingQueue()
        if (removed > 0) persistQueueState()
        return removed
    }

    /** Inserta una cancion justo despues de la actual (como "Agregar a la cola" en Spotify). */
    fun addToPlayNext(song: Song) {
        if (queueManager.isEmpty()) {
            setPlaylist(listOf(song), 0)
            return
        }
        playbackEngine.cancelCrossfadeIfAny()
        if (queueManager.addToPlayNext(song)) {
            persistQueueState()
        }
    }

    // ---------- Reproduccion (delega en PlaybackEngine) ----------

    fun isPlaying(): Boolean = playbackEngine.isPlaying()

    fun getCurrentPosition(): Int = playbackEngine.getCurrentPosition()

    fun getDuration(): Int = playbackEngine.getDuration()

    fun seekTo(positionMs: Int) {
        playbackEngine.seekTo(positionMs)
        notifier.updatePlaybackState(isPlaying(), positionMs.toLong())
    }

    fun togglePlayPause() {
        if (isPlaying()) pause() else play()
    }

    fun play() {
        playbackEngine.play()
    }

    fun pause() {
        playbackEngine.pause()
    }

    // ---------- Sleep timer (delega en SleepTimerManager) ----------

    fun setSleepTimerMinutes(minutes: Int) = sleepTimer.setMinutes(minutes)

    fun setSleepTimerEndOfSong() = sleepTimer.setEndOfSong()

    fun cancelSleepTimer() = sleepTimer.cancel()

    fun isSleepTimerActive(): Boolean = sleepTimer.isActive()

    fun isSleepTimerEndOfSongActive(): Boolean = sleepTimer.isEndOfSongActive()

    fun getSleepTimerRemainingMs(): Long = sleepTimer.getRemainingMs()

    fun playNext() {
        if (queueManager.isEmpty()) return
        playbackEngine.cancelCrossfadeIfAny()
        val nextIndex = queueManager.nextIndexFrom(queueManager.getCurrentIndex())
        if (nextIndex >= 0) playbackEngine.playSongAt(nextIndex)
    }

    fun playPrevious() {
        if (queueManager.isEmpty()) return
        playbackEngine.cancelCrossfadeIfAny()
        val previousIndex = queueManager.previousIndexFrom(queueManager.getCurrentIndex())
        if (previousIndex >= 0) playbackEngine.playSongAt(previousIndex)
    }

    // ---------- Callbacks de PlaybackEngine: aqui es donde se conectan
    // notificacion, MediaSession y los repositorios de estado. ----------

    private fun handleSongStarted(song: Song, index: Int, reason: PlaybackEngine.SongStartReason) {
        queueManager.setCurrentIndex(index)
        persistQueueState()

        notifier.updateMediaMetadata(
            song = song,
            exoDurationMs = playbackEngine.currentExoDurationMs(),
            isStillCurrent = { queueManager.getCurrentSong()?.id == song.id },
            onArtReady = { refreshNotification() }
        )

        val isPlayingNow = reason != PlaybackEngine.SongStartReason.RESTORED

        listener?.onSongChanged(song, index)
        if (reason != PlaybackEngine.SongStartReason.RESTORED) {
            PlayCountRepository.incrementPlayCount(applicationContext, song.id)
        }
        if (reason == PlaybackEngine.SongStartReason.NEW) {
            PlaybackStateRepository.saveLastSong(applicationContext, song.id, 0L)
        }
        listener?.onPlaybackStateChanged(isPlayingNow)
        notifier.updatePlaybackState(isPlayingNow, playbackEngine.getCurrentPosition().toLong())

        startForeground(NOTIFICATION_ID, notifier.buildNotification(song, isPlayingNow))
        updateWidgets()
    }

    private fun handlePlaybackStateChanged(isPlaying: Boolean) {
        listener?.onPlaybackStateChanged(isPlaying)
        refreshNotification()
        notifier.updatePlaybackState(isPlaying, getCurrentPosition().toLong())
        if (!isPlaying) {
            getCurrentSong()?.let {
                PlaybackStateRepository.saveLastSong(applicationContext, it.id, getCurrentPosition().toLong())
            }
        }
    }

    private fun handleTrackEnded() {
        if (sleepTimer.consumeEndOfSongIfActive()) {
            handlePlaybackStateChanged(false)
            return
        }
        if (queueManager.getPlaybackMode() == PlaybackMode.REPEAT_ONE) {
            playbackEngine.playSongAt(queueManager.getCurrentIndex())
        } else {
            playNext()
        }
    }

    /** No escribe en SharedPreferences 2 veces por segundo: solo cada ~5s mientras suena musica. */
    private fun maybeSavePlaybackStateThrottled(currentMs: Long) {
        if (!isPlaying()) return
        val now = System.nanoTime()
        if (now - lastPlaybackStateSaveNanos >= 5_000_000_000L) {
            lastPlaybackStateSaveNanos = now
            getCurrentSong()?.let { song ->
                PlaybackStateRepository.saveLastSong(applicationContext, song.id, currentMs)
            }
        }
    }

    private fun persistQueueState() {
        QueueStateRepository.save(
            context = applicationContext,
            queueIds = queueManager.getSongList().map { it.id },
            originalQueueIds = queueManager.getOriginalSongList().map { it.id },
            currentIndex = queueManager.getCurrentIndex(),
            currentSongId = queueManager.getCurrentSong()?.id,
            playbackMode = queueManager.getPlaybackMode()
        )
    }

    private fun persistQueueStateBlocking() {
        QueueStateRepository.saveBlocking(
            context = applicationContext,
            queueIds = queueManager.getSongList().map { it.id },
            originalQueueIds = queueManager.getOriginalSongList().map { it.id },
            currentIndex = queueManager.getCurrentIndex(),
            currentSongId = queueManager.getCurrentSong()?.id,
            playbackMode = queueManager.getPlaybackMode()
        )
    }

    private fun refreshNotification() {
        val song = getCurrentSong() ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notifier.buildNotification(song, isPlaying()))
        updateWidgets()
    }

    /** Refresca el widget de pantalla de inicio (si el usuario agrego uno) con la cancion y el estado actuales. */
    private fun updateWidgets() {
        MusicWidgetProvider.pushUpdate(applicationContext, getCurrentSong(), isPlaying())
    }

    private fun stopPlaybackAndService() {
        PlaybackStateRepository.clearLastSong(applicationContext)
        QueueStateRepository.clear(applicationContext)
        playbackEngine.releasePlayer()
        listener?.onPlaybackStateChanged(false)
        notifier.updatePlaybackState(false, 0L)
        MusicWidgetProvider.pushUpdate(applicationContext, getCurrentSong(), false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        persistQueueStateBlocking()
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimer.cancel()
        getCurrentSong()?.let {
            PlaybackStateRepository.saveLastSongBlocking(applicationContext, it.id, getCurrentPosition().toLong())
        }
        persistQueueStateBlocking()
        playbackEngine.releasePlayer()
        notifier.release()
    }
}