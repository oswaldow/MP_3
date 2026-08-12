package com.learnlayout.mp_3

import android.app.RecoverableSecurityException
import android.graphics.drawable.AnimationDrawable
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

class SongListActivity : AppCompatActivity(), MusicService.PlaybackListener {

    private lateinit var rootCoordinator: CoordinatorLayout
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var rvSongs: RecyclerView
    private lateinit var rvPlaylists: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var tvAppName: TextView
    private lateinit var ivMascot: ImageView
    private lateinit var ivMonito: ImageView
    private lateinit var llInlineSearch: View
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var btnSort: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var tabSongs: TextView
    private lateinit var tabPlaylists: TextView
    private lateinit var homeView: View
    private lateinit var homeController: HomeController

    private lateinit var playerPanel: FrameLayout
    private lateinit var groupExpanded: View
    private lateinit var groupMini: View
    private lateinit var ivMiniAlbumArt: ImageView
    private lateinit var tvMiniTitle: TextView
    private lateinit var tvMiniArtist: TextView
    private lateinit var btnMiniPlayPause: ImageButton
    private lateinit var btnMiniPlayMode: ImageButton
    private lateinit var circularMiniProgress: CircularProgressView
    private lateinit var btnPanelBack: ImageButton
    private lateinit var btnPanelSleepTimer: ImageButton
    private lateinit var btnPanelLyricsSync: ImageButton
    private lateinit var btnPanelQueue: ImageButton
    private lateinit var ivPanelAlbumArt: ImageView
    private lateinit var audioSpectrumView: AudioSpectrumView
    private lateinit var viewPanelArtBanner: View
    private lateinit var tvPanelSongTitle: TextView
    private lateinit var tvPanelArtist: TextView
    private lateinit var sbPanelProgress: WaveformSeekBar
    private lateinit var tvPanelCurrentTime: TextView
    private lateinit var tvPanelTotalTime: TextView
    private lateinit var btnPanelPrevious: ImageButton
    private lateinit var btnPanelPlayPause: ImageButton
    private lateinit var btnPanelNext: ImageButton
    private lateinit var llPanelControls: View

    // ---------- Panel de letra deslizable ----------
    private lateinit var lyricsCoordinator: View
    private lateinit var lyricsPanel: FrameLayout
    private lateinit var rvLyricsPanel: RecyclerView
    private lateinit var btnSaveLyrics: ImageButton

    private lateinit var songAdapter: SongAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private lateinit var btnPanelFavorite: ImageButton

    private var allSongs: List<Song> = emptyList()
    private var currentSort: SortType = SortType.TITLE
    private var searchQuery: String = ""

    private val playlistDialogs by lazy {
        PlaylistDialogs(
            context = this,
            isPlaylistsTabActive = { topBarController.isPlaylistsTabActive },
            onPlaylistsChanged = { loadPlaylists() },
            onSongMetadataChanged = {
                lyricsPanelController.resetSongId()
                loadSongs()
            },
            onDeleteSongFromDevice = { song -> requestDeleteSongFromDevice(song) }
        )
    }

    private var musicService: MusicService? = null
    private var isBound = false

    private var pendingCoverPlaylistId: String? = null
    private var pendingSongList: List<Song>? = null
    private var pendingStartIndex: Int = 0

    // Cancion pendiente de borrar del dispositivo mientras se espera la
    // confirmacion del usuario (dialogo del sistema en API 29+, o el
    // permiso de escritura en versiones viejas). Ver requestDeleteSongFromDevice.
    private var pendingDeleteSong: Song? = null

    private val queueSheet by lazy {
        QueueSheetController(
            activity = this,
            getMusicService = { musicService },
            getAccentColor = { playerPanelController.getAccentColor() },
            onModeChanged = {
                musicService?.let { playerPanelController.updateModeButtonIcon(it.getPlaybackMode()) }
            }
        )
    }

    private val lyricsPanelController: LyricsPanelController by lazy {
        LyricsPanelController(
            activity = this,
            lyricsCoordinator = lyricsCoordinator,
            lyricsPanel = lyricsPanel,
            rvLyricsPanel = rvLyricsPanel,
            btnSaveLyrics = btnSaveLyrics,
            btnPanelLyricsSync = btnPanelLyricsSync,
            groupExpanded = groupExpanded,
            llPanelControls = llPanelControls
        )
    }

    private val playerPanelController: PlayerPanelController by lazy {
        PlayerPanelController(
            activity = this,
            getMusicService = { musicService },
            playerPanel = playerPanel,
            groupExpanded = groupExpanded,
            groupMini = groupMini,
            ivMiniAlbumArt = ivMiniAlbumArt,
            tvMiniTitle = tvMiniTitle,
            tvMiniArtist = tvMiniArtist,
            btnMiniPlayPause = btnMiniPlayPause,
            btnMiniPlayMode = btnMiniPlayMode,
            circularMiniProgress = circularMiniProgress,
            btnPanelBack = btnPanelBack,
            btnPanelSleepTimer = btnPanelSleepTimer,
            btnPanelQueue = btnPanelQueue,
            btnPanelFavorite = btnPanelFavorite,
            btnPanelLyricsSync = btnPanelLyricsSync,
            ivPanelAlbumArt = ivPanelAlbumArt,
            audioSpectrumView = audioSpectrumView,
            viewPanelArtBanner = viewPanelArtBanner,
            tvPanelSongTitle = tvPanelSongTitle,
            tvPanelArtist = tvPanelArtist,
            sbPanelProgress = sbPanelProgress,
            tvPanelCurrentTime = tvPanelCurrentTime,
            tvPanelTotalTime = tvPanelTotalTime,
            btnPanelPrevious = btnPanelPrevious,
            btnPanelPlayPause = btnPanelPlayPause,
            btnPanelNext = btnPanelNext,
            onExpanded = {
                lyricsPanelController.onPlayerPanelExpanded()
                startMonitoAnimation()
            },
            onCollapsed = {
                lyricsPanelController.onPlayerPanelCollapsed()
                resetMonitoToTop()
            },
            onShowQueue = { queueSheet.show() },
            onFavoriteToggled = {
                if (::homeController.isInitialized && homeView.visibility == View.VISIBLE) homeController.refresh()
                if (topBarController.isPlaylistsTabActive) {
                    loadPlaylists()
                }
            },
            onAlbumArtLongPress = { song ->
                AlbumArtPickerDialog(this, song) { chosenSong, bitmap ->
                    AlbumArtRepository.applyOverride(
                        this, chosenSong, bitmap,
                        object : AlbumArtRepository.Callback {
                            override fun onCoverReady(bmp: android.graphics.Bitmap) {
                                playerPanelController.applyAlbumArtOverride(chosenSong, bmp)
                            }
                        }
                    )
                }.show()
            },
            onAlbumArtChanged = { bitmap ->
                if (bitmap != null) {
                    lyricsPanelController.applyAlbumArtColor(bitmap)
                } else {
                    lyricsPanelController.applyAlbumArtFallback()
                }
            },
            onAccentColorChanged = { queueSheet.refreshModeButtons() }
        )
    }

    private val topBarController by lazy {
        TopBarController(
            activity = this,
            rootLayout = rootLayout,
            tvAppName = tvAppName,
            ivMascot = ivMascot,
            llInlineSearch = llInlineSearch,
            etSearch = etSearch,
            btnSearch = btnSearch,
            btnSort = btnSort,
            btnSettings = btnSettings,
            tabSongs = tabSongs,
            tabPlaylists = tabPlaylists,
            rvSongs = rvSongs,
            rvPlaylists = rvPlaylists,
            tvEmptyState = tvEmptyState,
            onSearchQueryChanged = { query ->
                searchQuery = query
                applyFilterAndSort()
            },
            onSortSelected = { type ->
                currentSort = type
                PlaybackStateRepository.saveSortType(this, type)
                applyFilterAndSort()
            },
            onSongsTabSelected = { applyFilterAndSort() },
            onPlaylistsTabSelected = { loadPlaylists() },
            onHomeRequested = { showHome() }
        )
    }

    enum class SortType { TITLE, ARTIST, DURATION, DATE_ADDED, MOST_PLAYED }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val miniProgressPoller = object : Runnable {
        override fun run() {
            val service = musicService
            if (service != null && playerPanelController.isVisible) {
                val current = service.getCurrentPosition()
                val total = service.getDuration()

                playerPanelController.updateProgress(current, total)
                queueSheet.updateProgress(current, total)
                lyricsPanelController.syncWithPosition(current.toLong())
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    private val pickCoverLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val playlistId = pendingCoverPlaylistId
        if (uri != null && playlistId != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Algunos selectores no soportan permiso persistente, se ignora.
            }
            PlaylistRepository.setCoverImage(this, playlistId, uri.toString())
            loadPlaylists()
        }
        pendingCoverPlaylistId = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            musicService?.setListener(this@SongListActivity)
            isBound = true

            val pending = pendingSongList
            if (pending != null) {
                pendingSongList = null
                musicService?.setPlaylist(pending, pendingStartIndex)
                playerPanelController.expandWhenReady()
            } else {
                val current = musicService?.getCurrentSong()
                if (current != null) {
                    showMiniPlayer(current, musicService?.isPlaying() == true)
                    songAdapter.setCurrentPlayingId(current.id)
                } else {
                    tryRestoreLastSong()
                }
            }

            musicService?.let { playerPanelController.updateModeButtonIcon(it.getPlaybackMode()) }
            startMiniProgressPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results.entries.firstOrNull {
            it.key == Manifest.permission.READ_MEDIA_AUDIO ||
                    it.key == Manifest.permission.READ_EXTERNAL_STORAGE
        }?.value == true

        if (audioGranted) {
            loadSongs()
        } else {
            Toast.makeText(
                this,
                "Se necesita permiso para leer archivos de audio",
                Toast.LENGTH_LONG
            ).show()
            tvEmptyState.visibility = View.VISIBLE
        }
    }

    // Lanza el dialogo de confirmacion del sistema para borrar el archivo
    // (Android 10 via RecoverableSecurityException, Android 11+ via
    // MediaStore.createDeleteRequest). Si el usuario acepta, el sistema ya
    // borro el archivo y solo falta limpiar los datos propios de la app.
    private val deleteSongIntentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val song = pendingDeleteSong
        pendingDeleteSong = null
        if (result.resultCode == RESULT_OK && song != null) {
            finalizeSongDeletion(song)
        } else {
            Toast.makeText(this, "No se elimino la cancion", Toast.LENGTH_SHORT).show()
        }
    }

    // Solo se usa en Android 9 (API 28) o menos, donde borrar un archivo de
    // otra app con MediaStore todavia requiere este permiso en tiempo real.
    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val song = pendingDeleteSong
        if (granted && song != null) {
            performDeleteFromDevice(song)
        } else {
            pendingDeleteSong = null
            Toast.makeText(
                this,
                "Se necesita permiso para eliminar el archivo",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_list)

        PlaylistRepository.ensureFavoritesPlaylist(this)

        bindViews()
        topBarController.setup()
        setupHome()
        setupPlayerPanel()
        setupLyricsPanel()
        setupEdgeToEdge()
        setupBackPress()

        currentSort = PlaybackStateRepository.getSortType(this)

        checkPermissionsAndLoad()

        val serviceIntent = Intent(this, MusicService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        topBarController.startMascotAnimation()
    }

    private var monitoBlinkRunnable: Runnable? = null

    private fun resetMonitoToTop() {
        // Se llama al cerrar el panel: cancela cualquier bajada/parpadeo
        // pendiente y deja al monito quieto en su frame inicial (arriba,
        // cuerda corta), para que la proxima vez que abras el panel no se
        // alcance a ver un instante el estado anterior (aterrizado) durante
        // el fade-in del onSlide.
        monitoBlinkRunnable?.let { uiHandler.removeCallbacks(it) }
        (ivMonito.drawable as? AnimationDrawable)?.stop()
        ivMonito.setImageResource(R.drawable.ic_monito_frame1)
    }

    private fun startMonitoAnimation() {
        // Si ya habia una bajada pendiente (p.ej. abriste/cerraste muy rapido),
        // se cancela para que no se encimen dos animaciones.
        monitoBlinkRunnable?.let { uiHandler.removeCallbacks(it) }

        ivMonito.setImageResource(R.drawable.anim_monito_descend)
        ivMonito.post {
            val descendDrawable = ivMonito.drawable as? AnimationDrawable ?: return@post
            descendDrawable.start()

            // AnimationDrawable no avisa cuando termina un oneshot, asi que
            // calculamos la duracion total sumando cada frame y programamos
            // el cambio a la animacion de parpadeo justo cuando acaba.
            var totalDuration = 0
            for (i in 0 until descendDrawable.numberOfFrames) {
                totalDuration += descendDrawable.getDuration(i)
            }

            val blinkRunnable = Runnable {
                ivMonito.setImageResource(R.drawable.anim_monito_blink)
                (ivMonito.drawable as? AnimationDrawable)?.start()
            }
            monitoBlinkRunnable = blinkRunnable
            uiHandler.postDelayed(blinkRunnable, totalDuration.toLong())
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        topBarController.dispatchTouchEvent(ev)
        guardLyricsPanelDrag(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun guardLyricsPanelDrag(ev: MotionEvent) {
        if (!playerPanelController.isReady) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (lyricsPanelController.isCoordinatorVisible &&
                    lyricsPanelController.isPointInside(ev.rawX, ev.rawY)
                ) {
                    playerPanelController.setDraggable(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                playerPanelController.setDraggable(true)
            }
        }
    }

    private fun bindViews() {
        rootCoordinator = findViewById(R.id.rootCoordinator)
        rootLayout = findViewById(R.id.rootSongListLayout)
        rvSongs = findViewById(R.id.rvSongs)
        rvPlaylists = findViewById(R.id.rvPlaylists)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvAppName = findViewById(R.id.tvAppName)
        ivMascot = findViewById(R.id.ivMascot)
        ivMonito = findViewById(R.id.ivMonito)
        llInlineSearch = findViewById(R.id.llInlineSearch)
        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        btnSort = findViewById(R.id.btnSort)
        btnSettings = findViewById(R.id.btnSettings)
        tabSongs = findViewById(R.id.tabSongs)
        tabPlaylists = findViewById(R.id.tabPlaylists)
        homeView = findViewById(R.id.homeView)

        playerPanel = findViewById(R.id.playerPanel)
        groupExpanded = findViewById(R.id.groupExpanded)
        groupMini = findViewById(R.id.groupMini)
        ivMiniAlbumArt = findViewById(R.id.ivMiniAlbumArt)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        tvMiniArtist = findViewById(R.id.tvMiniArtist)
        btnMiniPlayPause = findViewById(R.id.btnMiniPlayPause)
        btnMiniPlayMode = findViewById(R.id.btnMiniPlayMode)
        circularMiniProgress = findViewById(R.id.circularMiniProgress)
        btnPanelBack = findViewById(R.id.btnPanelBack)
        btnPanelSleepTimer = findViewById(R.id.btnPanelSleepTimer)
        btnPanelQueue = findViewById(R.id.btnPanelQueue)
        btnPanelFavorite = findViewById(R.id.btnPanelFavorite)
        btnPanelLyricsSync = findViewById(R.id.btnPanelLyricsSync)
        ivPanelAlbumArt = findViewById(R.id.ivPanelAlbumArt)
        audioSpectrumView = findViewById(R.id.audioSpectrumView)
        viewPanelArtBanner = findViewById(R.id.viewPanelArtBanner)
        tvPanelSongTitle = findViewById(R.id.tvPanelSongTitle)
        tvPanelArtist = findViewById(R.id.tvPanelArtist)
        sbPanelProgress = findViewById(R.id.sbPanelProgress)
        tvPanelCurrentTime = findViewById(R.id.tvPanelCurrentTime)
        tvPanelTotalTime = findViewById(R.id.tvPanelTotalTime)
        btnPanelPrevious = findViewById(R.id.btnPanelPrevious)
        btnPanelPlayPause = findViewById(R.id.btnPanelPlayPause)
        btnPanelNext = findViewById(R.id.btnPanelNext)
        llPanelControls = findViewById(R.id.llPanelControls)

        lyricsCoordinator = findViewById(R.id.lyricsCoordinator)
        lyricsPanel = findViewById(R.id.lyricsPanel)
        rvLyricsPanel = findViewById(R.id.rvLyricsPanel)
        btnSaveLyrics = findViewById(R.id.btnSaveLyrics)

        rvSongs.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(
            emptyList(),
            onItemClick = { position -> openPlayer(songAdapter.getCurrentList(), position) },
            onMenuClick = { position -> playlistDialogs.showSongItemMenu(songAdapter.getSongAt(position)) }
        )
        rvSongs.adapter = songAdapter

        ItemTouchHelper(
            SongSwipeToQueueCallback { position ->
                val song = songAdapter.getSongAt(position)
                musicService?.addToPlayNext(song)
                Toast.makeText(this, "Sonará a continuación", Toast.LENGTH_SHORT).show()
            }
        ).attachToRecyclerView(rvSongs)

        rvPlaylists.layoutManager = LinearLayoutManager(this)
        playlistAdapter = PlaylistAdapter(
            playlists = emptyList(),
            getSongsForPlaylist = { playlist -> getSongsForPlaylist(playlist) },
            onAddNewClick = { playlistDialogs.showCreatePlaylistDialog(null) },
            onItemClick = { playlist -> openPlaylistDetail(playlist) },
            onDeleteClick = { playlist -> playlistDialogs.confirmDeletePlaylist(playlist) },
            onCoverClick = { playlist -> requestCoverImage(playlist) }
        )
        rvPlaylists.adapter = playlistAdapter
    }

    private fun setupHome() {
        homeController = HomeController(
            context = this,
            root = homeView,
            getAllSongs = { allSongs },
            getCurrentSong = { musicService?.getCurrentSong() },
            isPlaying = { musicService?.isPlaying() == true },
            onPlaySong = { song ->
                val index = allSongs.indexOfFirst { it.id == song.id }
                if (index >= 0) openPlayer(allSongs, index)
            },
            onOpenSongs = { showSongsHomeTarget() }
        )

        findViewById<View>(R.id.btnHomeSongs).setOnClickListener { showSongsHomeTarget() }
        findViewById<View>(R.id.btnHomePlaylists).setOnClickListener { showPlaylistsHomeTarget() }
        findViewById<View>(R.id.btnHomeFavorites).setOnClickListener {
            openPlaylistDetail(PlaylistRepository.getPlaylistById(this, PlaylistRepository.FAVORITES_PLAYLIST_ID)
                ?: return@setOnClickListener)
        }
        findViewById<View>(R.id.btnHomeRecent).setOnClickListener {
            openPlaylistDetail(Playlist(RECENT_PLAYLIST_ID, RECENT_PLAYLIST_NAME,
                PlayCountRepository.getRecentlyPlayedSongIds(this, AUTO_PLAYLIST_LIMIT).toMutableList()))
        }
        findViewById<View>(R.id.btnHomeMostPlayed).setOnClickListener {
            openPlaylistDetail(Playlist(MOST_PLAYED_PLAYLIST_ID, MOST_PLAYED_PLAYLIST_NAME,
                PlayCountRepository.getMostPlayedSongIds(this, AUTO_PLAYLIST_LIMIT).toMutableList()))
        }

        // La Activity abre directamente en Home. Las listas siguen disponibles
        // desde los accesos de Home y conservan las dos pestañas existentes.
        showHome()
    }

    private fun showHome() {
        // Primero bloqueamos/ocultamos las listas y cancelamos cualquier
        // animacion de cambio de pestaña. Esto evita que una animacion
        // pendiente vuelva a hacer visible rvSongs despues de entrar a Home.
        rvSongs.animate().cancel()
        rvPlaylists.animate().cancel()
        rvSongs.translationX = 0f
        rvPlaylists.translationX = 0f
        rvSongs.visibility = View.GONE
        rvPlaylists.visibility = View.GONE
        tvEmptyState.visibility = View.GONE

        topBarController.setHomeActive(true)
        homeView.visibility = View.VISIBLE
        homeView.bringToFront()
        findViewById<View>(R.id.llTabSelector).visibility = View.GONE
        btnSearch.visibility = View.GONE
        btnSort.visibility = View.GONE
        btnSettings.visibility = View.VISIBLE
        homeController.refresh()
    }

    private fun showSongsHomeTarget() {
        homeView.visibility = View.GONE
        findViewById<View>(R.id.llTabSelector).visibility = View.VISIBLE
        topBarController.openSongsFromHome()
    }

    private fun showPlaylistsHomeTarget() {
        homeView.visibility = View.GONE
        findViewById<View>(R.id.llTabSelector).visibility = View.VISIBLE
        topBarController.openPlaylistsFromHome()
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(rootCoordinator) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            rootLayout.setPadding(0, systemBars.top, 0, 0)

            playerPanelController.applyTopInset(systemBars.top)
            playerPanelController.applyBottomInset(systemBars.bottom)
            lyricsPanelController.applyWindowInsets(systemBars.top, rootCoordinator.height)

            playerPanelController.updatePeekHeight()
            insets
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this) {
            if (lyricsPanelController.isExpanded) {
                lyricsPanelController.collapse()
            } else if (playerPanelController.isReady && playerPanelController.isExpanded) {
                playerPanelController.collapse()
            } else if (homeView.visibility != View.VISIBLE) {
                // Desde Canciones/Playlists, el primer Back regresa al Home
                // en lugar de cerrar la Activity.
                showHome()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun getSongsForPlaylist(playlist: Playlist): List<Song> {
        val songsById = allSongs.associateBy { it.id }
        return playlist.songIds.mapNotNull { songsById[it] }
    }

    companion object {
        // IDs de las playlists automaticas de historial. No viven en
        // PlaylistRepository: se recalculan cada vez a partir de
        // PlayCountRepository, por eso llevan un prefijo "auto_".
        const val RECENT_PLAYLIST_ID = "auto_recent"
        const val MOST_PLAYED_PLAYLIST_ID = "auto_most_played"
        const val RECENT_PLAYLIST_NAME = "Recientes"
        const val MOST_PLAYED_PLAYLIST_NAME = "Mas escuchadas"

        private const val AUTO_PLAYLIST_LIMIT = 50

        // Bandera para que, al reproducir una cancion desde
        // PlaylistDetailActivity, el panel del reproductor se expanda
        // solo al volver a esta pantalla (que sigue viva en el back stack).
        var expandPlayerOnResume: Boolean = false
    }

    // ---------- Panel del reproductor ----------

    private fun setupPlayerPanel() {
        playerPanelController.setup()
    }

    // ---------- Panel de letra deslizable (estilo Spotify) ----------

    private fun setupLyricsPanel() {
        lyricsPanelController.setup()
    }

    private fun startMiniProgressPolling() {
        uiHandler.removeCallbacks(miniProgressPoller)
        uiHandler.post(miniProgressPoller)
    }

    private fun stopMiniProgressPolling() {
        uiHandler.removeCallbacks(miniProgressPoller)
    }

    // ---------- Datos y permisos ----------

    private fun checkPermissionsAndLoad() {
        val permissionsNeeded = mutableListOf<String>()

        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, audioPermission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(audioPermission)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isEmpty()) {
            loadSongs()
        } else {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    private fun loadSongs() {
        val t0 = System.currentTimeMillis()
        val rawSongs = SongRepository.getAllSongs(this)
        val t1 = System.currentTimeMillis()
        android.util.Log.d("PERF_DEBUG", "getAllSongs(): ${t1 - t0} ms, ${rawSongs.size} canciones")

        val overrides = SongMetadataRepository.getAllOverrides(this)
        allSongs = rawSongs.map { song ->
            val override = overrides[song.id]
            if (override != null) {
                song.copy(
                    title = override.first.ifBlank { song.title },
                    artist = override.second.ifBlank { song.artist }
                )
            } else {
                song
            }
        }
        val t2 = System.currentTimeMillis()
        android.util.Log.d("PERF_DEBUG", "overrides x${rawSongs.size}: ${t2 - t1} ms")

        if (allSongs.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
            rvSongs.visibility = View.GONE
            return
        }

        applyFilterAndSort()
        if (::homeController.isInitialized && homeView.visibility == View.VISIBLE) homeController.refresh()
        val t3 = System.currentTimeMillis()
        android.util.Log.d("PERF_DEBUG", "loadSongs() TOTAL: ${t3 - t0} ms")
    }

    private fun tryRestoreLastSong() {
        val lastSongId = PlaybackStateRepository.getLastSongId(this)
        if (lastSongId == -1L) return

        val song = allSongs.firstOrNull { it.id == lastSongId } ?: return
        val positionMs = PlaybackStateRepository.getLastPositionMs(this)

        musicService?.restorePlaylist(listOf(song), 0, positionMs)
        showMiniPlayer(song, false)
        songAdapter.setCurrentPlayingId(song.id)
    }

    private fun applyFilterAndSort() {
        if (topBarController.isPlaylistsTabActive) return

        val tStart = System.currentTimeMillis()
        var list = allSongs

        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.artist.contains(searchQuery, ignoreCase = true)
            }
        }
        val tFilter = System.currentTimeMillis()

        list = when (currentSort) {
            SortType.TITLE -> list.sortedBy { it.title.lowercase() }
            SortType.ARTIST -> list.sortedBy { it.artist.lowercase() }
            SortType.DURATION -> list.sortedBy { it.duration }
            SortType.DATE_ADDED -> list.sortedByDescending { it.dateAdded }
            SortType.MOST_PLAYED -> {
                val counts = PlayCountRepository.getAllPlayCounts(this)
                list.sortedByDescending { counts[it.id] ?: 0 }
            }
        }
        val tSort = System.currentTimeMillis()

        songAdapter.updateData(list)
        val tAdapter = System.currentTimeMillis()

        val hasResults = list.isNotEmpty()
        tvEmptyState.visibility = if (hasResults) View.GONE else View.VISIBLE

        // HOME y la lista de canciones comparten el mismo espacio.
        // Al cargar/filtrar las canciones, no debemos volver a hacer visible
        // rvSongs si Home está activo; de lo contrario la lista aparece
        // detrás del contenido de Home.
        if (homeView.visibility == View.VISIBLE) {
            rvSongs.visibility = View.GONE
        } else if (!topBarController.isPlaylistsTabActive) {
            rvSongs.visibility = if (hasResults) View.VISIBLE else View.GONE
        }

        musicService?.getCurrentSong()?.let {
            songAdapter.setCurrentPlayingId(it.id)
        }
        val tEnd = System.currentTimeMillis()

        android.util.Log.d(
            "PERF_DEBUG",
            "applyFilterAndSort(query='$searchQuery', sort=$currentSort, total=${allSongs.size}, resultado=${list.size}) -> " +
                    "filter=${tFilter - tStart}ms sort=${tSort - tFilter}ms " +
                    "adapter.updateData=${tAdapter - tSort}ms resto=${tEnd - tAdapter}ms TOTAL=${tEnd - tStart}ms"
        )
    }

    private fun loadPlaylists() {
        val playlists = buildAutoPlaylists() + PlaylistRepository.getAllPlaylists(this)
        playlistAdapter.updateData(playlists)
    }

    private fun buildAutoPlaylists(): List<Playlist> {
        val autoPlaylists = mutableListOf<Playlist>()

        val recentIds = PlayCountRepository.getRecentlyPlayedSongIds(this, AUTO_PLAYLIST_LIMIT)
        if (recentIds.isNotEmpty()) {
            autoPlaylists.add(Playlist(RECENT_PLAYLIST_ID, RECENT_PLAYLIST_NAME, recentIds.toMutableList()))
        }

        val mostPlayedIds = PlayCountRepository.getMostPlayedSongIds(this, AUTO_PLAYLIST_LIMIT)
        if (mostPlayedIds.isNotEmpty()) {
            autoPlaylists.add(Playlist(MOST_PLAYED_PLAYLIST_ID, MOST_PLAYED_PLAYLIST_NAME, mostPlayedIds.toMutableList()))
        }

        return autoPlaylists
    }

    private fun requestCoverImage(playlist: Playlist) {
        pendingCoverPlaylistId = playlist.id
        pickCoverLauncher.launch("image/*")
    }

    private fun openPlaylistDetail(playlist: Playlist) {
        val intent = Intent(this, PlaylistDetailActivity::class.java)
        intent.putExtra("playlist_id", playlist.id)
        startActivity(intent)
    }

    private fun openPlayer(playlist: List<Song>, startIndex: Int) {
        val service = musicService
        val tappedSong = playlist.getOrNull(startIndex)
        if (service != null) {
            // Si la cancion tocada es la misma que ya esta sonando, no la
            // reiniciamos con setPlaylist (que siempre arranca desde el
            // principio): solo abrimos el panel donde va quedo.
            val isSameSongPlaying = tappedSong != null && service.getCurrentSong()?.id == tappedSong.id
            Log.d("MP3_PANEL", "openPlayer: tappedSong='${tappedSong?.title}' isSameSongPlaying=$isSameSongPlaying thread=${Thread.currentThread().name}")
            if (!isSameSongPlaying) {
                Log.d("MP3_PANEL", "openPlayer: llamando service.setPlaylist(...)")
                service.setPlaylist(playlist, startIndex)
                Log.d("MP3_PANEL", "openPlayer: service.setPlaylist(...) retorno")
            }
            Log.d("MP3_PANEL", "openPlayer: llamando playerPanelController.expandWhenReady()")
            playerPanelController.expandWhenReady()
        } else {
            pendingSongList = playlist
            pendingStartIndex = startIndex
        }
    }

    // ---------- Eliminar cancion del dispositivo ----------

    /**
     * Punto de entrada desde el menu de tres puntos (PlaylistDialogs, ya
     * mostro su propio dialogo de confirmacion). Segun la version de
     * Android, borrar un archivo que la app no creo requiere un flujo
     * distinto: Android 11+ pide confirmacion nativa via
     * MediaStore.createDeleteRequest, Android 10 puede lanzar
     * RecoverableSecurityException al intentar borrar directo, y en
     * versiones anteriores alcanza con el permiso de escritura clasico.
     */
    private fun requestDeleteSongFromDevice(song: Song) {
        pendingDeleteSong = song
        performDeleteFromDevice(song)
    }

    private fun performDeleteFromDevice(song: Song) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(song.uri))
                deleteSongIntentSenderLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                try {
                    contentResolver.delete(song.uri, null, null)
                    pendingDeleteSong = null
                    finalizeSongDeletion(song)
                } catch (e: RecoverableSecurityException) {
                    deleteSongIntentSenderLauncher.launch(
                        IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                    )
                }
            }
            else -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        contentResolver.delete(song.uri, null, null)
                        pendingDeleteSong = null
                        finalizeSongDeletion(song)
                    } catch (e: SecurityException) {
                        pendingDeleteSong = null
                        Toast.makeText(this, "No se pudo eliminar el archivo", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    /**
     * El archivo ya se borro del dispositivo (MediaStore/almacenamiento):
     * ahora limpiamos lo que la app guarda sobre esa cancion (playlists,
     * nombre editado, letra guardada), la sacamos de la reproduccion
     * actual si era la que estaba sonando, y recargamos la lista.
     */
    private fun finalizeSongDeletion(song: Song) {
        PlaylistRepository.removeSongFromAllPlaylists(this, song.id)
        SongMetadataRepository.removeOverride(this, song.id)
        SavedLyricsRepository.remove(this, song.id)

        if (musicService?.getCurrentSong()?.id == song.id) {
            if ((musicService?.getSongList()?.size ?: 0) > 1) {
                musicService?.playNext()
            }
        }

        Toast.makeText(this, "\"${song.title}\" eliminada del dispositivo", Toast.LENGTH_SHORT).show()

        lyricsPanelController.resetSongId()
        loadSongs()
        if (topBarController.isPlaylistsTabActive) {
            loadPlaylists()
        }
    }

    private fun showMiniPlayer(song: Song, playing: Boolean) {
        Log.d("MP3_PANEL", "showMiniPlayer: song='${song.title}' playing=$playing thread=${Thread.currentThread().name}")
        playerPanelController.updateNowPlaying(song, playing)
        lyricsPanelController.updatePeekHeight()
        lyricsPanelController.loadForSong(song)
    }

    override fun onSongChanged(song: Song, index: Int) {
        Log.d("MP3_PANEL", "onSongChanged: song='${song.title}' index=$index thread(antes de runOnUiThread)=${Thread.currentThread().name}")
        runOnUiThread {
            Log.d("MP3_PANEL", "onSongChanged: dentro de runOnUiThread thread=${Thread.currentThread().name}")
            showMiniPlayer(song, musicService?.isPlaying() == true)
            songAdapter.setCurrentPlayingId(song.id)
            if (::homeController.isInitialized && homeView.visibility == View.VISIBLE) homeController.refresh()
            queueSheet.onSongChanged(song)
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            playerPanelController.updatePlaybackState(isPlaying)
        }
    }

    override fun onProgressChanged(currentMs: Int, totalMs: Int) {
        // El progreso se actualiza con miniProgressPoller.
    }

    override fun onStart() {
        super.onStart()
        musicService?.setListener(this)
        musicService?.getCurrentSong()?.let {
            showMiniPlayer(it, musicService?.isPlaying() == true)
            songAdapter.setCurrentPlayingId(it.id)
        }
        if (::homeController.isInitialized && homeView.visibility == View.VISIBLE) homeController.refresh()
        musicService?.let { playerPanelController.updateModeButtonIcon(it.getPlaybackMode()) }
        startMiniProgressPolling()

        if (topBarController.isPlaylistsTabActive) {
            loadPlaylists()
        }

        if (expandPlayerOnResume) {
            expandPlayerOnResume = false
            playerPanelController.expandWhenReady()
        }
    }

    override fun onStop() {
        super.onStop()
        stopMiniProgressPolling()
        musicService?.setListener(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMiniProgressPolling()
        queueSheet.dismiss()
        lyricsPanelController.cancelAnimations()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}