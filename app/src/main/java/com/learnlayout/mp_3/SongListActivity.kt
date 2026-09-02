package com.learnlayout.mp_3

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

class SongListActivity : AppCompatActivity(), MusicService.PlaybackListener {

    // true si este onCreate() redirigio a OnboardingActivity y no llego a
    // llamar setContentView(): en ese caso, onStart/onStop/onDestroy no
    // deben tocar ninguna vista (todas las propiedades "by lazy" de mas
    // abajo dependen de findViewById y truenan si la vista no existe).
    private var isRedirectingToOnboarding = false

    // Las vistas se resuelven con findViewById() al primer acceso (by lazy)
    // en lugar de lateinit + asignacion manual en bindViews(): mismo
    // resultado, menos codigo repetido. Como el primer acceso siempre
    // ocurre despues de setContentView() (bindViews() se llama justo
    // despues en onCreate), el comportamiento es identico al anterior.
    private val rootCoordinator: CoordinatorLayout by lazy { findViewById(R.id.rootCoordinator) }
    private val rootLayout: ConstraintLayout by lazy { findViewById(R.id.rootSongListLayout) }
    private val rvSongs: RecyclerView by lazy { findViewById(R.id.rvSongs) }
    private val rvPlaylists: RecyclerView by lazy { findViewById(R.id.rvPlaylists) }
    private val swipeRefreshSongList: SwipeRefreshLayout by lazy { findViewById(R.id.swipeRefreshSongList) }
    private val tvEmptyState: TextView by lazy { findViewById(R.id.tvEmptyState) }
    private val tvAppName: TextView by lazy { findViewById(R.id.tvAppName) }
    private val ivMascot: ImageView by lazy { findViewById(R.id.ivMascot) }
    private val llInlineSearch: View by lazy { findViewById(R.id.llInlineSearch) }
    private val etSearch: EditText by lazy { findViewById(R.id.etSearch) }
    private val btnSearch: ImageButton by lazy { findViewById(R.id.btnSearch) }
    private val btnSort: ImageButton by lazy { findViewById(R.id.btnSort) }
    private val btnSettings: LiquidGlassView by lazy {
        findViewById<LiquidGlassView>(R.id.btnSettings).also {
            it.setCornerRadiusDp(20f)
        }
    }
    private val ivSettingsIcon: ImageView by lazy { findViewById(R.id.ivSettingsIcon) }
    private val tabSongs: TextView by lazy { findViewById(R.id.tabSongs) }
    private val tabPlaylists: TextView by lazy { findViewById(R.id.tabPlaylists) }
    private val homeView: NestedScrollView by lazy { findViewById(R.id.homeView) }
    private val glassPanelsScrollHandler = Handler(Looper.getMainLooper())
    private val glassPanelsScrollRefresh = Runnable {
        if (::homeController.isInitialized) homeController.refreshGlassPanels()
    }
    private lateinit var homeController: HomeController
    private lateinit var homeNavigationController: HomeNavigationController

    private val playerPanel: FrameLayout by lazy { findViewById(R.id.playerPanel) }
    private val groupExpanded: View by lazy { findViewById(R.id.groupExpanded) }
    // LiquidGlassView (antes un LinearLayout normal) para que el mini
    // reproductor tenga el mismo efecto de vidrio esmerilado que los
    // paneles de Accesos rapidos del Home (ver setupHome/HomeController).
    private val groupMini: LiquidGlassView by lazy {
        findViewById<LiquidGlassView>(R.id.groupMini).also {
            // Esquinas rectas (0dp) en vez del radio por defecto (22dp):
            // como groupMini ocupa el ancho completo pegado arriba de
            // playerPanel, un rectangulo sin redondear siempre cubre por
            // completo el fondo de playerPanel (bg_mini_player_full),
            // sin importar el radio que tenga ese drawable.
            it.setCornerRadiusDp(0f)
        }
    }
    private val ivMiniAlbumArt: ImageView by lazy { findViewById(R.id.ivMiniAlbumArt) }
    private val tvMiniTitle: TextView by lazy { findViewById(R.id.tvMiniTitle) }
    private val tvMiniArtist: TextView by lazy { findViewById(R.id.tvMiniArtist) }
    private val btnMiniPlayPause: ImageButton by lazy { findViewById(R.id.btnMiniPlayPause) }
    private val btnMiniPlayMode: ImageButton by lazy { findViewById(R.id.btnMiniPlayMode) }
    private val circularMiniProgress: CircularProgressView by lazy { findViewById(R.id.circularMiniProgress) }
    private val btnPanelBack: ImageButton by lazy { findViewById(R.id.btnPanelBack) }
    private val btnPanelSleepTimer: ImageButton by lazy { findViewById(R.id.btnPanelSleepTimer) }
    private val btnPanelLyricsSync: ImageButton by lazy { findViewById(R.id.btnPanelLyricsSync) }
    private val btnPanelQueue: ImageButton by lazy { findViewById(R.id.btnPanelQueue) }
    // Nuevo icono "Agregar a playlist" de la fila superior del panel
    // expandido, junto a btnPanelQueue/btnPanelFavorite/btnPanelLyricsSync.
    // El id btnPanelAddToPlaylist todavia no existe en activity_song_list.xml:
    // falta agregarlo al layout (iteracion aparte) para que este findViewById
    // no truene en tiempo de ejecucion.
    private val btnPanelAddToPlaylist: ImageButton by lazy { findViewById(R.id.btnPanelAddToPlaylist) }
    private val ivPanelAlbumArt: ImageView by lazy { findViewById(R.id.ivPanelAlbumArt) }

    private val albumArtTransitionOverlay: FrameLayout by lazy { findViewById(R.id.flAlbumArtTransitionOverlay) }
    private val audioSpectrumView: AudioSpectrumView by lazy { findViewById(R.id.audioSpectrumView) }
    private val viewPanelArtBanner: View by lazy { findViewById(R.id.viewPanelArtBanner) }
    private val tvPanelSongTitle: TextView by lazy { findViewById(R.id.tvPanelSongTitle) }
    private val tvPanelArtist: TextView by lazy { findViewById(R.id.tvPanelArtist) }
    private val sbPanelProgress: WaveformSeekBar by lazy { findViewById(R.id.sbPanelProgress) }
    private val tvPanelCurrentTime: TextView by lazy { findViewById(R.id.tvPanelCurrentTime) }
    private val tvPanelTotalTime: TextView by lazy { findViewById(R.id.tvPanelTotalTime) }
    private val btnPanelPrevious: ImageButton by lazy { findViewById(R.id.btnPanelPrevious) }
    private val btnPanelPlayPause: ImageButton by lazy { findViewById(R.id.btnPanelPlayPause) }
    private val btnPanelNext: ImageButton by lazy { findViewById(R.id.btnPanelNext) }
    private val llPanelControls: View by lazy { findViewById(R.id.llPanelControls) }

    // ---------- Panel de letra deslizable ----------
    private val lyricsCoordinator: View by lazy { findViewById(R.id.lyricsCoordinator) }
    private val lyricsPanel: FrameLayout by lazy { findViewById(R.id.lyricsPanel) }
    private val rvLyricsPanel: RecyclerView by lazy { findViewById(R.id.rvLyricsPanel) }
    private val btnSaveLyrics: ImageButton by lazy { findViewById(R.id.btnSaveLyrics) }

    private lateinit var songAdapter: SongAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private val btnPanelFavorite: ImageButton by lazy { findViewById(R.id.btnPanelFavorite) }

    private var allSongs: List<Song> = emptyList()
    private var currentSort: SortType = SortType.TITLE
    private var isReverseOrder: Boolean = false
    private var searchQuery: String = ""

    private var songsLoaded = false
    private var pendingRestoreLastSong = false

    private val playlistDialogs by lazy {
        PlaylistDialogs(
            context = this,
            isPlaylistsTabActive = { topBarController.isPlaylistsTabActive },
            onPlaylistsChanged = { loadPlaylists() },
            onSongMetadataChanged = {
                refreshCurrentSongMetadata()
                loadSongs()
                if (queueSheet.isShowing) {
                    queueSheet.refreshList()
                }
            },
            onDeleteSongFromDevice = { song ->
                songDeletionController.requestDelete(song)
            }
        )
    }

    private var musicService: MusicService? = null
    private var isBound = false

    private var pendingCoverPlaylistId: String? = null

    private val musicServiceConnectionController by lazy {
        MusicServiceConnectionController(
            onServiceConnected = { service, pendingPlayback ->
                musicService = service
                musicService?.setListener(this@SongListActivity)
                isBound = true

                if (pendingPlayback != null) {
                    service.setPlaylist(
                        pendingPlayback.songs,
                        pendingPlayback.startIndex
                    )
                    playerPanelController.expandWhenReady()
                } else {
                    val current = service.getCurrentSong()
                    if (current != null) {
                        showMiniPlayer(current, service.isPlaying())
                        songAdapter.setCurrentPlayingId(current.id)
                    } else if (songsLoaded) {
                        tryRestoreLastSong()
                    } else {
                        // loadSongs() (background) todavia no termino: se
                        // marca para restaurar la cola en cuanto termine,
                        // en vez de intentarlo ahora con allSongs vacio.
                        pendingRestoreLastSong = true
                    }
                }

                playerPanelController.updateModeButtonIcon(service.getPlaybackMode())
                startMiniProgressPolling()
            },
            onServiceDisconnected = {
                musicService = null
                isBound = false
            }
        )
    }

    private val queueSheet: QueueSheetController by lazy {
        QueueSheetController(
            activity = this,
            getMusicService = { musicService },
            playlistDialogs = playlistDialogs,
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
            lyricsCoordinator = lyricsCoordinator,
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
            btnPanelAddToPlaylist = btnPanelAddToPlaylist,
            ivPanelAlbumArt = ivPanelAlbumArt,
            albumArtTransitionOverlay = albumArtTransitionOverlay,
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
            },
            onCollapsed = {
                lyricsPanelController.onPlayerPanelCollapsed()
            },
            onShowQueue = { queueSheet.show() },
            onFavoriteToggled = {
                if (::homeController.isInitialized && homeView.visibility == View.VISIBLE) homeController.refresh()
                if (topBarController.isPlaylistsTabActive) {
                    loadPlaylists()
                }
            },
            onAlbumArtLongPress = { song ->
                AlbumArtPickerDialog(
                    context = this,
                    song = song,
                    onCoverChosen = { chosenSong, bitmap ->
                        AlbumArtRepository.applyOverride(
                            this, chosenSong, bitmap,
                            object : AlbumArtRepository.Callback {
                                override fun onCoverReady(bmp: android.graphics.Bitmap) {
                                    playerPanelController.applyAlbumArtOverride(chosenSong, bmp)
                                }
                            }
                        )
                    },
                    onLyricsChosen = { chosenSong, result ->
                        // Eleccion manual desde el picker de candidatos: no
                        // debe pisarse sola con la letra embebida del archivo.
                        SavedLyricsRepository.saveManual(this, chosenSong.id, result)
                        persistLyricsToAudioFileIfPossible(chosenSong, result)
                        lyricsPanelController.reloadSavedLyrics(chosenSong)
                        Toast.makeText(this, "Letra guardada", Toast.LENGTH_SHORT).show()
                    }
                ).show()
            },
            onEditSongMetadata = { song ->
                playlistDialogs.showEditSongMetadataDialog(song)
            },
            onAddToPlaylist = { song ->
                playlistDialogs.showAddToPlaylistDialog(song)
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

    private val playbackProgressController: PlaybackProgressController by lazy {
        PlaybackProgressController(
            getMusicService = { musicService },
            isPlayerVisible = { playerPanelController.isVisible },
            onProgress = { currentMs, totalMs ->
                playerPanelController.updateProgress(currentMs, totalMs)
                queueSheet.updateProgress(currentMs, totalMs)
                lyricsPanelController.syncWithPosition(currentMs.toLong())
            }
        )
    }

    // Tema dinamico: null significa "sin cancion con caratula" -> usar el
    // fallback morado fijo (R.color.purple_primary). No purple_primary
    // como tal: cada consumidor decide su propio matiz de fallback si lo
    // necesita, pero por ahora todos comparten el mismo.
    private val accentColorListener: (Int?) -> Unit = { color ->
        val resolved = color ?: ContextCompat.getColor(this, R.color.purple_primary)
        topBarController.applyAccentColor(resolved)
        if (::homeController.isInitialized) homeController.applyAccentColor(resolved)
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
            ivSettingsIcon = ivSettingsIcon,
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
            isSortReversed = { isReverseOrder },
            onSortReverseToggled = {
                isReverseOrder = !isReverseOrder
                PlaybackStateRepository.saveSortReversed(this, isReverseOrder)
                applyFilterAndSort()
            },
            onSongsTabSelected = { applyFilterAndSort() },
            onPlaylistsTabSelected = { loadPlaylists() },
            onHomeRequested = { showHome() }
        )
    }

    enum class SortType { TITLE, ARTIST, DURATION, DATE_ADDED, MOST_PLAYED }

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

    private val deleteSongIntentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        songDeletionController.onDeleteIntentResult(result.resultCode)
    }

    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        songDeletionController.onWriteStoragePermissionResult(granted)
    }

    private val songDeletionController: SongDeletionController by lazy {
        SongDeletionController(
            activity = this,
            deleteSongIntentSenderLauncher = deleteSongIntentSenderLauncher,
            writeStoragePermissionLauncher = writeStoragePermissionLauncher,
            onSongDeleted = ::finalizeSongDeletion
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Primer arranque: mostramos el onboarding antes que nada y no
        // seguimos inicializando esta pantalla (se relanza al terminarlo).
        if (!SettingsRepository.isOnboardingCompleted(this)) {
            isRedirectingToOnboarding = true
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_song_list)

        PlaylistRepository.ensureFavoritesPlaylist(this)

        bindViews()
        topBarController.setup()
        setupHome()
        setupSwipeToRefresh()
        setupPlayerPanel()
        setupLyricsPanel()
        setupEdgeToEdge()
        setupBackPress()

        // Tema dinamico: tab activo (TopBarController) y botones de Home
        // (HomeController) siguen el color de la caratula de la cancion
        // actual. Se suscriben aqui (y se desuscriben en onDestroy) en vez
        // de en su propio setup() porque AppAccentColor es un singleton que
        // vive mas alla del ciclo de vida de esta Activity.
        AppAccentColor.addListener(accentColorListener)

        currentSort = PlaybackStateRepository.getSortType(this)
        isReverseOrder = PlaybackStateRepository.getSortReversed(this)

        checkPermissionsAndLoad()

        val serviceIntent = Intent(this, MusicService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(
            serviceIntent,
            musicServiceConnectionController.connection,
            Context.BIND_AUTO_CREATE
        )

        topBarController.startMascotAnimation()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        topBarController.dispatchTouchEvent(ev)
        guardLyricsPanelDrag(ev)
        // DEBUG TEMPORAL - tag: MP3_SwipeDebug
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            android.util.Log.d(
                "MP3_SwipeDebug",
                "SongListActivity.dispatchTouchEvent ACTION_DOWN en (${ev.rawX}, ${ev.rawY}) playerPanelController.isReady=${playerPanelController.isReady}"
            )
        }
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
                    // DEBUG TEMPORAL - tag: MP3_SwipeDebug
                    android.util.Log.d("MP3_SwipeDebug", "guardLyricsPanelDrag: setDraggable(false) - el panel de letra capturo el toque")
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                playerPanelController.setDraggable(true)
            }
        }
    }

    private fun bindViews() {
        // Las propiedades de vista (rootCoordinator, rvSongs, tvAppName,
        // etc.) ya se resuelven solas por su delegado "by lazy" la primera
        // vez que se usan; aqui solo queda configurar adapters y listeners.
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
        val tabSelector = findViewById<View>(R.id.llTabSelector)

        homeNavigationController = HomeNavigationController(
            homeView = homeView,
            tabSelector = tabSelector,
            rvSongs = rvSongs,
            rvPlaylists = rvPlaylists,
            emptyState = tvEmptyState,
            btnSearch = btnSearch,
            btnSort = btnSort,
            btnSettings = btnSettings,
            topBarController = topBarController,
            onHomeShown = {
                homeController.refresh()
            }
        )

        homeController = HomeController(
            context = this,
            root = homeView,
            // El fondo animado debe cubrir TODA la pantalla (Home,
            // Canciones y Playlists comparten el mismo contenedor), asi
            // que se lo pasamos explicito en vez de dejar que
            // HomeController lo infiera con homeView.parent: desde que
            // homeView quedo envuelto en swipeRefreshSongList (ver
            // setupSwipeToRefresh), su padre inmediato ya no es
            // rootLayout, sino el ConstraintLayout interno del
            // SwipeRefreshLayout.
            backgroundTarget = rootLayout,
            getAllSongs = { allSongs },
            getCurrentSong = { musicService?.getCurrentSong() },
            isPlaying = { musicService?.isPlaying() == true },
            onPlaySong = { song ->
                // Desde Home, las tres secciones (Recientemente reproducido,
                // Mas escuchadas y Agregadas recientemente) usan la MISMA
                // cola cronologica: todas las canciones de la biblioteca,
                // ordenadas por la fecha en que fueron agregadas.
                //
                // Se ordena de la mas antigua a la mas nueva para que, al
                // tocar una cancion, la siguiente sea exactamente la que
                // se agrego despues de ella. Las anteriores quedan arriba
                // de la actual en la cola y las posteriores quedan debajo.
                val homeQueue = allSongs
                    .sortedWith(
                        compareBy<Song> { it.dateAdded }
                            .thenBy { it.id }
                    )

                val index = homeQueue.indexOfFirst { it.id == song.id }

                if (index >= 0) {
                    val service = musicService
                    val current = service?.getCurrentSong()

                    if (service != null && current?.id == song.id) {
                        // La cancion ya esta sonando. Solo reconstruimos la
                        // cola sin reiniciar el audio ni perder la posicion.
                        service.replaceQueueKeepingCurrent(homeQueue)
                        playerPanelController.expandWhenReady()
                    } else {
                        openPlayer(homeQueue, index)
                    }
                }
            },
            onOpenSongs = { homeNavigationController.showSongs() },
            // groupMini vive fuera de homeView (es visible en las tres
            // pestañas), pero comparte el mismo fondo animado (rootLayout):
            // se agrega aqui para que se fotografie y se refresque junto
            // con el resto de los paneles liquid glass del Home.
            additionalGlassPanels = listOf(groupMini, btnSettings)
        )

        // Vuelve a fotografiar los paneles de vidrio cuando el scroll de
        // Home se detiene, para que el degradado que reflejan coincida
        // con su posicion real (ver refreshGlassPanels() en HomeController).
        homeView.setOnScrollChangeListener { _, _, _, _, _ ->
            glassPanelsScrollHandler.removeCallbacks(glassPanelsScrollRefresh)
            glassPanelsScrollHandler.postDelayed(glassPanelsScrollRefresh, 120L)
        }

        findViewById<View>(R.id.btnHomeSongs).setOnClickListener { homeNavigationController.showSongs() }
        findViewById<View>(R.id.btnHomePlaylists).setOnClickListener { homeNavigationController.showPlaylists() }
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
        homeNavigationController.showHome()
    }

    private fun showHome() {
        homeNavigationController.showHome()
    }

    /**
     * "Tirar para abajo" sobre Home, Canciones o Playlists (la que este
     * visible en ese momento) muestra el icono circular estandar de
     * actualizando y refresca esa seccion al instante: relee las
     * canciones (incluye ediciones de titulo/artista y las auto-playlists
     * de Mas escuchadas/Recientes) y las playlists manuales.
     *
     * El callback de scroll evita que el gesto dispare un refresh cuando
     * la lista visible no esta arriba del todo (por ejemplo, a mitad de
     * un scroll en Home): sin esto, arrastrar hacia abajo en pleno scroll
     * podria confundirse con el gesto de refrescar.
     */
    private fun setupSwipeToRefresh() {
        swipeRefreshSongList.setColorSchemeResources(R.color.spotify_green)
        swipeRefreshSongList.setProgressBackgroundColorSchemeResource(R.color.spotify_card)

        swipeRefreshSongList.setOnChildScrollUpCallback { _, _ ->
            when {
                homeView.visibility == View.VISIBLE -> homeView.canScrollVertically(-1)
                topBarController.isPlaylistsTabActive -> rvPlaylists.canScrollVertically(-1)
                else -> rvSongs.canScrollVertically(-1)
            }
        }

        swipeRefreshSongList.setOnRefreshListener {
            loadSongs()
            loadPlaylists()
        }
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
        playbackProgressController.start()
    }

    private fun stopMiniProgressPolling() {
        playbackProgressController.stop()
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

    /**
     * SongRepository.getAllSongs() (consulta MediaStore + limpieza de
     * artista por regex) corria entero en el hilo principal, cada vez que
     * se llamaba: al abrir la app, al tirar para refrescar, al borrar o
     * editar una cancion, etc. Con ~450+ canciones eso se sentia como una
     * traba. Ahora el escaneo corre en segundo plano y solo el resultado
     * ya calculado se aplica a las vistas en el hilo principal.
     *
     * songsLoaded/pendingRestoreLastSong: ver el comentario junto a esos
     * campos, arriba.
     */
    private fun loadSongs() {
        AppExecutors.runInBackground {
            val rawSongs = SongRepository.getAllSongs(this)

            val overrides = SongMetadataRepository.getAllOverrides(this)
            val result = rawSongs.map { song ->
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

            AppExecutors.runOnMain {
                if (isFinishing || isDestroyed) return@runOnMain

                allSongs = result
                songsLoaded = true

                if (allSongs.isEmpty()) {
                    tvEmptyState.visibility = View.VISIBLE
                    rvSongs.visibility = View.GONE
                } else {
                    applyFilterAndSort()
                    if (::homeController.isInitialized && homeView.visibility == View.VISIBLE) homeController.refresh()
                }

                // Antes esto lo apagaba quien llamaba a loadSongs() justo
                // despues (por ejemplo, el gesto de "tirar para
                // refrescar"), lo cual funcionaba porque loadSongs() era
                // sincrono. Ahora que es async, se apaga aca, cuando el
                // resultado ya esta aplicado. En el resto de los casos
                // (loadSongs() no vino de un pull-to-refresh) esto es un
                // no-op: isRefreshing ya estaba en false.
                swipeRefreshSongList.isRefreshing = false

                if (pendingRestoreLastSong) {
                    pendingRestoreLastSong = false
                    tryRestoreLastSong()
                }
            }
        }
    }

    /**
     * Restaura la cola de reproduccion al reabrir la app.
     *
     * Se llama cuando el MusicService ya esta conectado pero no tiene
     * ninguna cancion cargada: eso pasa cuando Android mato el proceso en
     * segundo plano (por ejemplo, al deslizar la app fuera de la lista de
     * apps recientes) y el servicio arranca de cero.
     *
     * Antes esto solo restauraba la ULTIMA cancion sonada como una cola de
     * una sola cancion (PlaybackStateRepository), asi que al volver a abrir
     * la app el resto de la cola desaparecia. Ahora se usa
     * QueueStateRepository, que guarda la cola completa (orden, lista
     * original para shuffle, indice actual y modo de reproduccion) cada vez
     * que cambia, para reconstruirla tal cual estaba.
     */
    private fun tryRestoreLastSong() {
        val queueState = QueueStateRepository.get(this)

        if (queueState != null) {
            val songsById = allSongs.associateBy { it.id }

            val restoredQueue = queueState.queueIds.mapNotNull { songsById[it] }

            if (restoredQueue.isNotEmpty()) {
                val restoredOriginal =
                    queueState.originalQueueIds
                        .mapNotNull { songsById[it] }
                        .ifEmpty { restoredQueue }

                val currentSong = songsById[queueState.currentSongId]

                val startIndex =
                    if (currentSong != null) {
                        restoredQueue.indexOfFirst { it.id == currentSong.id }
                            .let { if (it >= 0) it else 0 }
                    } else {
                        queueState.currentIndex.coerceIn(0, restoredQueue.lastIndex)
                    }

                val positionMs = PlaybackStateRepository.getLastPositionMs(this)

                musicService?.restorePersistedQueue(
                    songs = restoredQueue,
                    originalSongs = restoredOriginal,
                    startIndex = startIndex,
                    mode = queueState.playbackMode,
                    positionMs = positionMs
                )

                val restoredCurrent = restoredQueue[startIndex]
                showMiniPlayer(restoredCurrent, false)
                songAdapter.setCurrentPlayingId(restoredCurrent.id)
                return
            }
        }

        // Red de seguridad: no habia cola persistida (instalacion nueva,
        // datos borrados, etc.) o ninguna de sus canciones sigue existiendo
        // en la biblioteca. Se restaura al menos la ultima cancion
        // individual, igual que se hacia antes.
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

        var list = allSongs

        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.artist.contains(searchQuery, ignoreCase = true)
            }
        }

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

        if (isReverseOrder) {
            list = list.reversed()
        }

        songAdapter.updateData(list)

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
            if (!isSameSongPlaying) {
                service.setPlaylist(playlist, startIndex)
            }
            playerPanelController.expandWhenReady()
        } else {
            musicServiceConnectionController.queuePlayback(playlist, startIndex)
        }
    }

    // ---------- Eliminar cancion del dispositivo ----------

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

    /**
     * Si la cancion editada es la que esta sonando, reemplaza sus metadatos
     * dentro de MusicService sin reiniciar ExoPlayer. El propio servicio
     * notifica al listener para que titulo, artista, letra, caratula,
     * notificacion y widget se actualicen en el mismo instante.
     */
    private fun refreshCurrentSongMetadata() {
        val service = musicService ?: return
        val current = service.getCurrentSong() ?: return
        val updated = SongMetadataRepository.apply(this, current)
        if (updated.title == current.title && updated.artist == current.artist) return
        service.updateSongMetadata(updated.id, updated.title, updated.artist)
    }

    private fun persistLyricsToAudioFileIfPossible(song: Song, result: LyricsResult) {
        if (!SongFileTagWriter.hasManageStoragePermission(this)) return
        val appContext = applicationContext
        AppExecutors.runInBackground {
            SongFileTagWriter.writeToFile(appContext, song, lyricsResult = result)
        }
    }

    private fun showMiniPlayer(song: Song, playing: Boolean) {
        playerPanelController.updateNowPlaying(song, playing)
        lyricsPanelController.updatePeekHeight()
        lyricsPanelController.loadForSong(song)
        // groupMini es visible en las tres pestañas (Home, Canciones,
        // Playlists), asi que su fondo liquid glass debe mantenerse al
        // dia con la cancion sonando sin importar cual este activa. Los
        // demas usos de homeController.refresh() estan condicionados a
        // homeView.visibility == VISIBLE (evitan trabajo de mas en
        // vistas no visibles), pero eso dejaba a groupMini fotografiando
        // el degradado neutro por defecto cada vez que el mini player se
        // mostraba desde una ruta que no pasaba por refresh() completo
        // (por ejemplo al reconectar con el servicio y restaurar una
        // cancion que ya estaba sonando).
        if (::homeController.isInitialized) {
            homeController.updateAmbientBackground(song)
        }
    }

    override fun onSongChanged(song: Song, index: Int) {
        runOnUiThread {
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
        if (isRedirectingToOnboarding) return
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
        if (isRedirectingToOnboarding) return
        stopMiniProgressPolling()
        musicService?.setListener(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRedirectingToOnboarding) return
        glassPanelsScrollHandler.removeCallbacks(glassPanelsScrollRefresh)
        AppAccentColor.removeListener(accentColorListener)
        stopMiniProgressPolling()
        queueSheet.dismiss()
        lyricsPanelController.cancelAnimations()
        if (isBound) {
            unbindService(musicServiceConnectionController.connection)
            isBound = false
        }
    }
}