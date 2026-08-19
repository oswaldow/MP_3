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

        // Instancia en vivo del servicio, mientras este atado (bound) o
        // corriendo en primer plano. Se usa desde componentes que NO
        // tienen (ni deberian tener) un bind normal contra el servicio,
        // como WhatsAppNotificationReaderService: necesita poder pedir
        // ducking en cuanto arranca a leer un mensaje, sin depender de
        // que alguna Activity este en pantalla en ese momento.
        @Volatile
        private var runningInstance: MusicService? = null

        fun getRunningInstance(): MusicService? = runningInstance
    }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this

        // Debe ir antes de crear playbackEngine: es lo que carga desde
        // SharedPreferences si el ecualizador estaba activado y con que
        // valores, para que la cadena de audio arranque ya configurada.
        // Antes esto nunca se llamaba en ningun lugar de la app, asi que
        // el estado guardado jamas se leia y el ecualizador volvia a
        // quedar desactivado cada vez que el proceso de la app moria en
        // segundo plano (tras unas horas, por ejemplo).
        EqualizerRepository.init(applicationContext)

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
        if (songs.isNotEmpty()) {
            playbackEngine.playSongAt(startIndex)
        }
        persistQueueState()
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

    /** Reconstruye la ultima cancion reproducida sin contarla como reproduccion nueva ni arrancarla en automatico. */
    fun restorePlaylist(songs: List<Song>, startIndex: Int, positionMs: Long) {
        if (songs.isEmpty()) return
        queueManager.restorePlaylist(songs, startIndex)
        playbackEngine.restoreSongAt(queueManager.getCurrentIndex(), positionMs)
        persistQueueState()
    }

    /**
     * Reconstruye la cola COMPLETA (no solo la cancion actual) tal cual
     * habia quedado guardada en QueueStateRepository: mismo orden, misma
     * lista original para shuffle, mismo indice actual y mismo modo de
     * reproduccion. No se cuenta como reproduccion nueva ni arranca en
     * automatico.
     *
     * Se usa al reabrir la app despues de que Android mato el proceso en
     * segundo plano (por ejemplo, al deslizarla fuera de la lista de apps
     * recientes): MusicService arranca desde cero con la cola vacia, y
     * esto la deja exactamente como el usuario la dejo.
     */
    fun restorePersistedQueue(
        songs: List<Song>,
        originalSongs: List<Song>,
        startIndex: Int,
        mode: PlaybackMode,
        positionMs: Long
    ) {
        if (songs.isEmpty()) return
        queueManager.restorePersistedQueue(songs, originalSongs, startIndex, mode)
        playbackEngine.restoreSongAt(queueManager.getCurrentIndex(), positionMs)
        persistQueueState()
    }

    fun getCurrentSong(): Song? = queueManager.getCurrentSong()

    fun getSongList(): List<Song> = queueManager.getSongList()

    fun getCurrentIndex(): Int = queueManager.getCurrentIndex()


    /**
     * Actualiza los metadatos locales de una cancion sin reiniciar el audio.
     * Si es la cancion actual, refresca inmediatamente UI, notificacion y widget.
     */
    fun updateSongMetadata(songId: Long, title: String, artist: String) {
        val updated = queueManager.updateSongMetadata(songId, title, artist) ?: return
        val current = queueManager.getCurrentSong()
        if (current?.id != songId) return

        notifier.updateMediaMetadata(
            song = updated,
            exoDurationMs = playbackEngine.currentExoDurationMs(),
            isStillCurrent = { queueManager.getCurrentSong()?.id == songId },
            onArtReady = { refreshNotification() }
        )

        listener?.onSongChanged(updated, queueManager.getCurrentIndex())
        notifier.updatePlaybackState(isPlaying(), playbackEngine.getCurrentPosition().toLong())
        startForeground(NOTIFICATION_ID, notifier.buildNotification(updated, isPlaying()))
        updateWidgets()
    }

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
            playbackEngine.playSongAt(index)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playbackEngine.cancelCrossfadeIfAny()
        queueManager.moveQueueItem(fromIndex, toIndex)
        persistQueueState()
    }

    fun removeQueueItem(index: Int): Boolean {
        if (index == queueManager.getCurrentIndex()) return false
        playbackEngine.cancelCrossfadeIfAny()
        val removed = queueManager.removeQueueItem(index)
        if (removed) {
            persistQueueState()
        }
        return removed
    }

    fun clearUpcomingQueue(): Int {
        playbackEngine.cancelCrossfadeIfAny()
        val removedCount = queueManager.clearUpcomingQueue()
        if (removedCount > 0) {
            persistQueueState()
        }
        return removedCount
    }

    /** Inserta una cancion justo despues de la actual (como "Agregar a la cola" en Spotify). */
    fun addToPlayNext(song: Song) {
        if (queueManager.isEmpty()) {
            setPlaylist(listOf(song), 0)
            return
        }
        playbackEngine.cancelCrossfadeIfAny()
        queueManager.addToPlayNext(song)
        persistQueueState()
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

    // ---------- Ducking (delega en PlaybackEngine) ----------
    // Usado por WhatsAppNotificationReaderService via getRunningInstance()
    // para bajar/subir el volumen de la musica mientras se lee un mensaje
    // en voz alta, sin pausarla.

    fun duckForSpeech() = playbackEngine.duckForSpeech()

    fun unduckAfterSpeech() = playbackEngine.unduckAfterSpeech()

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

        // La cancion (y por tanto el indice actual dentro de la cola)
        // acaba de cambiar: se persiste la cola completa para que, si el
        // proceso muere justo despues, se restaure con el indice correcto
        // en vez de reiniciar en la primera cancion.
        persistQueueState()
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

    /**
     * Escribe en disco (de forma NO bloqueante) la cola completa tal como
     * esta ahora mismo: IDs de canciones en orden, IDs de la lista original
     * (para reconstruir shuffle), indice actual, ID de la cancion actual y
     * modo de reproduccion.
     *
     * Se llama cada vez que la cola cambia de alguna forma (nueva cola,
     * reordenar, quitar, agregar a continuacion, cambiar de modo, cambiar
     * de cancion). Es lo que permite que QueueStateRepository.get() siempre
     * tenga una version reciente de la cola disponible, sin importar en que
     * momento Android decida matar el proceso.
     */
    private fun persistQueueState() {
        val songs = queueManager.getSongList()

        if (songs.isEmpty()) {
            QueueStateRepository.clear(applicationContext)
            return
        }

        QueueStateRepository.save(
            context = applicationContext,
            queueIds = songs.map { it.id },
            originalQueueIds = queueManager.getOriginalSongList().map { it.id },
            currentIndex = queueManager.getCurrentIndex(),
            currentSongId = queueManager.getCurrentSong()?.id,
            playbackMode = queueManager.getPlaybackMode()
        )
    }

    /** Version bloqueante de persistQueueState(), para onTaskRemoved()/onDestroy(). */
    private fun persistQueueStateBlocking() {
        val songs = queueManager.getSongList()

        if (songs.isEmpty()) {
            QueueStateRepository.clear(applicationContext)
            return
        }

        QueueStateRepository.saveBlocking(
            context = applicationContext,
            queueIds = songs.map { it.id },
            originalQueueIds = queueManager.getOriginalSongList().map { it.id },
            currentIndex = queueManager.getCurrentIndex(),
            currentSongId = queueManager.getCurrentSong()?.id,
            playbackMode = queueManager.getPlaybackMode()
        )
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
        // para asegurar que la posicion Y la cola completa queden en disco
        // antes de que eso pase. Antes solo se guardaba la cancion+posicion
        // (PlaybackStateRepository), nunca la cola completa
        // (QueueStateRepository), asi que al volver a abrir la app la cola
        // se reconstruia con una sola cancion en vez de con todas las que
        // habia antes de salir.
        getCurrentSong()?.let {
            PlaybackStateRepository.saveLastSongBlocking(applicationContext, it.id, getCurrentPosition().toLong())
        }
        persistQueueStateBlocking()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (runningInstance === this) runningInstance = null
        sleepTimer.cancel()
        getCurrentSong()?.let {
            PlaybackStateRepository.saveLastSongBlocking(applicationContext, it.id, getCurrentPosition().toLong())
        }
        persistQueueStateBlocking()
        playbackEngine.releasePlayer()
        notifier.release()
    }
}