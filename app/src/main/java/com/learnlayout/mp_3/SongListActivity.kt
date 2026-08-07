package com.learnlayout.mp_3

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
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale
import kotlin.math.abs

class SongListActivity : AppCompatActivity(), MusicService.PlaybackListener {

    private lateinit var rootCoordinator: CoordinatorLayout
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var rvSongs: RecyclerView
    private lateinit var rvPlaylists: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var tvAppName: TextView
    private lateinit var llInlineSearch: View
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var btnSort: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var tabSongs: TextView
    private lateinit var tabPlaylists: TextView

    private lateinit var playerPanel: FrameLayout
    private lateinit var panelBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var groupExpanded: View
    private lateinit var groupMini: View
    private lateinit var tvMiniTitle: TextView
    private lateinit var tvMiniArtist: TextView
    private lateinit var btnMiniPlayPause: ImageButton
    private lateinit var btnMiniPlayMode: ImageButton
    private lateinit var circularMiniProgress: CircularProgressView
    private lateinit var btnPanelBack: ImageButton
    private lateinit var btnPanelQueue: ImageButton
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
    // FIX: el XML declara lyricsPanel como <FrameLayout> (el header flota
    // como overlay encima del RecyclerView), no como LinearLayout. El tipo
    // aqui debe coincidir con el inflado real o findViewById lanza
    // ClassCastException en tiempo de ejecucion.
    private lateinit var lyricsPanel: FrameLayout
    private lateinit var lyricsPanelBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var llLyricsHeader: View
    private lateinit var btnLyricsPanelClose: ImageButton
    private lateinit var rvLyricsPanel: RecyclerView

    private var lyricsAdapter: LyricsLineAdapter? = null
    private var lyricsSongId: Long? = null
    private var lyricsRequestId: Int = 0

    private lateinit var songAdapter: SongAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private lateinit var btnPanelFavorite: ImageButton

    private lateinit var swipeGestureDetector: GestureDetector

    private var allSongs: List<Song> = emptyList()
    private var currentSort: SortType = SortType.TITLE
    private var searchQuery: String = ""
    private var isSearchVisible: Boolean = false
    private var isPlaylistsTabActive: Boolean = false

    private var musicService: MusicService? = null
    private var isBound = false

    private var pendingCoverPlaylistId: String? = null
    private var pendingSongList: List<Song>? = null
    private var pendingStartIndex: Int = 0

    private var isUserSeekingPanel: Boolean = false

    private var activeQueueDialog: BottomSheetDialog? = null
    private var queueTvTitle: TextView? = null
    private var queuePlayPauseBtn: ImageButton? = null
    private var queueProgressBar: ProgressBar? = null
    private var queueRecyclerView: RecyclerView? = null
    private var queueBtnModeNormal: ImageButton? = null
    private var queueBtnModeRepeat: ImageButton? = null
    private var queueBtnModeShuffle: ImageButton? = null
    private var queueTouchHelper: ItemTouchHelper? = null

    private var baseGroupMiniPaddingBottom: Int = 0
    private var baseGroupExpandedPaddingBottom: Int = 0

    enum class SortType { TITLE, ARTIST, DURATION, DATE_ADDED, MOST_PLAYED }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val miniProgressPoller = object : Runnable {
        override fun run() {
            val service = musicService
            if (service != null && playerPanel.visibility == View.VISIBLE) {
                val current = service.getCurrentPosition()
                val total = service.getDuration()

                circularMiniProgress.setProgress(current, total)
                btnMiniPlayPause.setImageResource(
                    if (service.isPlaying()) R.drawable.ic_pause_small else R.drawable.ic_play_small
                )

                if (!isUserSeekingPanel) {
                    sbPanelProgress.max = if (total > 0) total else 0
                    sbPanelProgress.progress = current
                }
                tvPanelCurrentTime.text = formatTime(current.toLong())
                tvPanelTotalTime.text = formatTime(total.toLong())
                btnPanelPlayPause.setImageResource(
                    if (service.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow
                )

                queueProgressBar?.let {
                    it.max = if (total > 0) total else 0
                    it.progress = current
                }
                queuePlayPauseBtn?.setImageResource(
                    if (service.isPlaying()) R.drawable.ic_pause_small else R.drawable.ic_play_small
                )

                syncLyricsWithPosition(current.toLong())
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
                expandPlayerPanelWhenReady()
            } else {
                musicService?.getCurrentSong()?.let { song ->
                    showMiniPlayer(song, musicService?.isPlaying() == true)
                    songAdapter.setCurrentPlayingId(song.id)
                }
            }

            musicService?.let { updateModeButtonIcon(it.getPlaybackMode()) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_list)

        PlaylistRepository.ensureFavoritesPlaylist(this)

        bindViews()
        setupTopBar()
        setupTabs()
        setupSearch()
        setupPlayerPanel()
        setupLyricsPanel()
        setupEdgeToEdge()
        setupBackPress()
        setupSwipeToPlaylists()

        checkPermissionsAndLoad()

        val serviceIntent = Intent(this, MusicService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::swipeGestureDetector.isInitialized) {
            swipeGestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun bindViews() {
        rootCoordinator = findViewById(R.id.rootCoordinator)
        rootLayout = findViewById(R.id.rootSongListLayout)
        rvSongs = findViewById(R.id.rvSongs)
        rvPlaylists = findViewById(R.id.rvPlaylists)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvAppName = findViewById(R.id.tvAppName)
        llInlineSearch = findViewById(R.id.llInlineSearch)
        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        btnSort = findViewById(R.id.btnSort)
        btnSettings = findViewById(R.id.btnSettings)
        tabSongs = findViewById(R.id.tabSongs)
        tabPlaylists = findViewById(R.id.tabPlaylists)

        playerPanel = findViewById(R.id.playerPanel)
        groupExpanded = findViewById(R.id.groupExpanded)
        groupMini = findViewById(R.id.groupMini)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        tvMiniArtist = findViewById(R.id.tvMiniArtist)
        btnMiniPlayPause = findViewById(R.id.btnMiniPlayPause)
        btnMiniPlayMode = findViewById(R.id.btnMiniPlayMode)
        circularMiniProgress = findViewById(R.id.circularMiniProgress)
        btnPanelBack = findViewById(R.id.btnPanelBack)
        btnPanelQueue = findViewById(R.id.btnPanelQueue)
        btnPanelFavorite = findViewById(R.id.btnPanelFavorite)
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
        llLyricsHeader = findViewById(R.id.llLyricsHeader)
        btnLyricsPanelClose = findViewById(R.id.btnLyricsPanelClose)
        rvLyricsPanel = findViewById(R.id.rvLyricsPanel)
        // Colapsado: el usuario no puede hacer scroll manual (la posicion
        // la controla la cancion), asi el gesto de swipe siempre se le pasa
        // al panel para expandirlo/colapsarlo en vez de que el RecyclerView
        // lo capture como intento de scroll interno.
        // Expandido: se permite scroll manual libre para poder leer toda
        // la letra sin que el auto-scroll lo interrumpa.
        rvLyricsPanel.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically(): Boolean {
                return ::lyricsPanelBehavior.isInitialized &&
                        lyricsPanelBehavior.state == BottomSheetBehavior.STATE_EXPANDED
            }
        }
        rvLyricsPanel.isNestedScrollingEnabled = false
        // El item animator por defecto de RecyclerView queda desactivado:
        // el resaltado de la linea activa (alpha + escala) ahora lo anima
        // directamente LyricsLineAdapter sobre el ViewHolder visible, en
        // paralelo al reposicionamiento instantaneo del scroll. Esto evita
        // el conflicto que tenia antes el DefaultItemAnimator (su propia
        // animacion de cambio se peleaba con el scroll y hacia parecer que
        // la linea "avanzaba" en vez de quedarse fija arriba).
        rvLyricsPanel.itemAnimator = null


        baseGroupMiniPaddingBottom = groupMini.paddingBottom
        baseGroupExpandedPaddingBottom = groupExpanded.paddingBottom

        rvSongs.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(
            emptyList(),
            onItemClick = { position -> openPlayer(songAdapter.getCurrentList(), position) },
            onMenuClick = { position -> showAddToPlaylistDialog(songAdapter.getSongAt(position)) }
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
            onAddNewClick = { showCreatePlaylistDialog(null) },
            onItemClick = { playlist -> openPlaylistDetail(playlist) },
            onDeleteClick = { playlist -> confirmDeletePlaylist(playlist) },
            onCoverClick = { playlist -> requestCoverImage(playlist) }
        )
        rvPlaylists.adapter = playlistAdapter
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(rootCoordinator) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            rootLayout.setPadding(0, systemBars.top, 0, 0)

            groupMini.setPadding(
                groupMini.paddingLeft,
                groupMini.paddingTop,
                groupMini.paddingRight,
                baseGroupMiniPaddingBottom + systemBars.bottom
            )

            groupExpanded.setPadding(
                groupExpanded.paddingLeft,
                groupExpanded.paddingTop,
                groupExpanded.paddingRight,
                baseGroupExpandedPaddingBottom + systemBars.bottom
            )

            updatePeekHeight()
            insets
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this) {
            if (::lyricsPanelBehavior.isInitialized && lyricsPanelBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                lyricsPanelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            } else if (::panelBehavior.isInitialized && panelBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                panelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
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

    private fun setupTopBar() {
        btnSearch.setOnClickListener {
            toggleInlineSearch()
        }

        btnSort.setOnClickListener {
            showSortPopup()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun toggleInlineSearch() {
        isSearchVisible = !isSearchVisible

        if (isSearchVisible) {
            tvAppName.visibility = View.GONE
            llInlineSearch.visibility = View.VISIBLE
            btnSort.visibility = View.GONE
            btnSettings.visibility = View.GONE
            btnSearch.setImageResource(R.drawable.ic_close)
            etSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        } else {
            llInlineSearch.visibility = View.GONE
            tvAppName.visibility = View.VISIBLE
            btnSort.visibility = View.VISIBLE
            btnSettings.visibility = View.VISIBLE
            btnSearch.setImageResource(R.drawable.ic_search)
            etSearch.setText("")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        }
    }

    private fun showSortPopup() {
        val popupView = layoutInflater.inflate(R.layout.popup_sort_menu, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.elevation = 16f

        val tvTitle: TextView = popupView.findViewById(R.id.tvSortTitle)
        val tvArtist: TextView = popupView.findViewById(R.id.tvSortArtist)
        val tvDuration: TextView = popupView.findViewById(R.id.tvSortDuration)
        val tvDateAdded: TextView = popupView.findViewById(R.id.tvSortDateAdded)
        val tvMostPlayed: TextView = popupView.findViewById(R.id.tvSortMostPlayed)

        tvTitle.setOnClickListener {
            currentSort = SortType.TITLE
            applyFilterAndSort()
            popupWindow.dismiss()
        }
        tvArtist.setOnClickListener {
            currentSort = SortType.ARTIST
            applyFilterAndSort()
            popupWindow.dismiss()
        }
        tvDuration.setOnClickListener {
            currentSort = SortType.DURATION
            applyFilterAndSort()
            popupWindow.dismiss()
        }
        tvDateAdded.setOnClickListener {
            currentSort = SortType.DATE_ADDED
            applyFilterAndSort()
            popupWindow.dismiss()
        }
        tvMostPlayed.setOnClickListener {
            currentSort = SortType.MOST_PLAYED
            applyFilterAndSort()
            popupWindow.dismiss()
        }

        popupWindow.showAsDropDown(btnSort, -180, 12)
    }

    private fun setupTabs() {
        tabSongs.setOnClickListener {
            selectSongsTab()
        }

        tabPlaylists.setOnClickListener {
            selectPlaylistsTab()
        }
    }

    private fun setupSwipeToPlaylists() {
        swipeGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || isSearchVisible) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                val isMostlyHorizontal = abs(diffX) > abs(diffY) * 1.5f
                val isFastEnough = abs(velocityX) > SWIPE_MIN_VELOCITY

                if (!isMostlyHorizontal || !isFastEnough) return false

                // El dedo se mueve hacia la izquierda para traer la siguiente
                // pestana (Playlists) desde la derecha; hacia la derecha para
                // regresar (Canciones), como un ViewPager.
                val isSwipeLeft = diffX < -SWIPE_MIN_DISTANCE
                val isSwipeRight = diffX > SWIPE_MIN_DISTANCE

                if (isSwipeLeft && !isPlaylistsTabActive) {
                    selectPlaylistsTab()
                    return true
                }

                if (isSwipeRight && isPlaylistsTabActive) {
                    selectSongsTab()
                    return true
                }

                return false
            }
        })
    }

    companion object {
        private const val SWIPE_MIN_DISTANCE = 120
        private const val SWIPE_MIN_VELOCITY = 200
        private const val TAB_SLIDE_DURATION = 300L

        // IDs de las playlists automaticas de historial. No viven en
        // PlaylistRepository: se recalculan cada vez a partir de
        // PlayCountRepository, por eso llevan un prefijo "auto_".
        const val RECENT_PLAYLIST_ID = "auto_recent"
        const val MOST_PLAYED_PLAYLIST_ID = "auto_most_played"
        const val RECENT_PLAYLIST_NAME = "Recientes"
        const val MOST_PLAYED_PLAYLIST_NAME = "Mas escuchadas"

        private const val AUTO_PLAYLIST_LIMIT = 50
    }

    private fun selectSongsTab() {
        if (!isPlaylistsTabActive) return

        isPlaylistsTabActive = false

        tabSongs.setBackgroundResource(R.drawable.bg_tab_selected)
        tabSongs.setTextColor(ContextCompat.getColor(this, R.color.text_primary_light))
        tabPlaylists.background = null
        tabPlaylists.setTextColor(ContextCompat.getColor(this, R.color.text_secondary_light))

        btnSearch.visibility = View.VISIBLE

        applyFilterAndSort()

        // toPlaylists = false: Canciones entra desde la izquierda, Playlists
        // sale por la derecha.
        slideTabs(outgoing = rvPlaylists, incoming = rvSongs, toPlaylists = false)
    }

    private fun selectPlaylistsTab() {
        if (isPlaylistsTabActive) return

        isPlaylistsTabActive = true

        tabPlaylists.setBackgroundResource(R.drawable.bg_tab_selected)
        tabPlaylists.setTextColor(ContextCompat.getColor(this, R.color.text_primary_light))
        tabSongs.background = null
        tabSongs.setTextColor(ContextCompat.getColor(this, R.color.text_secondary_light))

        tvEmptyState.visibility = View.GONE

        if (isSearchVisible) {
            isSearchVisible = false
            llInlineSearch.visibility = View.GONE
            tvAppName.visibility = View.VISIBLE
            btnSort.visibility = View.VISIBLE
            btnSettings.visibility = View.VISIBLE
            btnSearch.setImageResource(R.drawable.ic_search)
            etSearch.setText("")
        }
        btnSearch.visibility = View.GONE

        loadPlaylists()

        // toPlaylists = true: Playlists entra desde la derecha, Canciones
        // sale por la izquierda.
        slideTabs(outgoing = rvSongs, incoming = rvPlaylists, toPlaylists = true)
    }

    /**
     * Desliza dos vistas como si fueran paginas de un ViewPager: la vista
     * "incoming" arranca completamente fuera de pantalla (a la derecha si
     * toPlaylists es true, a la izquierda si es false) y se desliza hasta su
     * posicion normal, mientras "outgoing" se desliza hacia el lado opuesto
     * hasta salir de pantalla, donde se oculta con GONE.
     */
    private fun slideTabs(outgoing: View, incoming: View, toPlaylists: Boolean) {
        val width = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val widthF = width.toFloat()

        outgoing.animate().cancel()
        incoming.animate().cancel()

        val incomingStartX = if (toPlaylists) widthF else -widthF
        val outgoingEndX = if (toPlaylists) -widthF else widthF

        incoming.translationX = incomingStartX
        incoming.visibility = View.VISIBLE

        outgoing.animate()
            .translationX(outgoingEndX)
            .setDuration(TAB_SLIDE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.translationX = 0f
            }
            .start()

        incoming.animate()
            .translationX(0f)
            .setDuration(TAB_SLIDE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                applyFilterAndSort()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ---------- Panel del reproductor ----------

    private fun setupPlayerPanel() {
        panelBehavior = BottomSheetBehavior.from(playerPanel)
        panelBehavior.isHideable = false
        panelBehavior.skipCollapsed = false

        panelBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        groupMini.alpha = 0f
                        groupExpanded.alpha = 1f
                        groupMini.visibility = View.INVISIBLE
                        groupExpanded.visibility = View.VISIBLE
                        lyricsCoordinator.visibility = View.VISIBLE
                        updateLyricsPeekHeight()
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        groupMini.alpha = 1f
                        groupExpanded.alpha = 0f
                        groupMini.visibility = View.VISIBLE
                        groupExpanded.visibility = View.INVISIBLE
                        updatePeekHeight()
                        if (::lyricsPanelBehavior.isInitialized) {
                            lyricsPanelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                        }
                        lyricsCoordinator.visibility = View.GONE
                    }
                    else -> {
                        groupMini.visibility = View.VISIBLE
                        groupExpanded.visibility = View.VISIBLE
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val progress = slideOffset.coerceIn(0f, 1f)
                groupMini.alpha = (1f - (progress / 0.5f)).coerceIn(0f, 1f)
                groupExpanded.alpha = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
            }
        })

        panelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        groupMini.alpha = 1f
        groupExpanded.alpha = 0f
        groupMini.visibility = View.VISIBLE
        groupExpanded.visibility = View.INVISIBLE

        groupMini.setOnClickListener {
            if (panelBehavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                panelBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        btnPanelBack.setOnClickListener {
            panelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

        btnPanelQueue.setOnClickListener {
            showQueueSheet()
        }

        btnPanelFavorite.setOnClickListener {
            toggleCurrentSongFavorite()
        }

        btnPanelPlayPause.setOnClickListener {
            musicService?.togglePlayPause()
        }

        btnPanelPrevious.setOnClickListener {
            musicService?.playPrevious()
        }

        btnPanelNext.setOnClickListener {
            musicService?.playNext()
        }

        btnMiniPlayPause.setOnClickListener {
            musicService?.togglePlayPause()
        }

        btnMiniPlayMode.setOnClickListener {
            val service = musicService ?: return@setOnClickListener
            val newMode = service.cyclePlaybackMode()
            updateModeButtonIcon(newMode)

            val message = when (newMode) {
                MusicService.PlaybackMode.NORMAL -> "Reproduccion normal"
                MusicService.PlaybackMode.REPEAT_ONE -> "Repitiendo cancion actual"
                MusicService.PlaybackMode.SHUFFLE -> "Reproduccion aleatoria"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        sbPanelProgress.listener = object : WaveformSeekBar.OnWaveformSeekListener {
            override fun onProgressChanged(progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvPanelCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch() {
                isUserSeekingPanel = true
            }

            override fun onStopTrackingTouch(progress: Int) {
                isUserSeekingPanel = false
                musicService?.seekTo(progress)
            }
        }
    }

    private fun updatePeekHeight() {
        groupMini.post {
            val height = groupMini.height
            if (height > 0 && height != panelBehavior.peekHeight) {
                panelBehavior.peekHeight = height
                playerPanel.requestLayout()
            }
        }
    }

    private fun expandPlayerPanelWhenReady() {
        playerPanel.doOnLayout {
            panelBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun updateModeButtonIcon(mode: MusicService.PlaybackMode) {
        when (mode) {
            MusicService.PlaybackMode.NORMAL -> {
                btnMiniPlayMode.setImageResource(R.drawable.ic_repeat)
                btnMiniPlayMode.setBackgroundResource(R.drawable.bg_icon_button_circle)
            }
            MusicService.PlaybackMode.REPEAT_ONE -> {
                btnMiniPlayMode.setImageResource(R.drawable.ic_repeat_one)
                btnMiniPlayMode.setBackgroundResource(R.drawable.btn_primary_round)
            }
            MusicService.PlaybackMode.SHUFFLE -> {
                btnMiniPlayMode.setImageResource(R.drawable.ic_shuffle)
                btnMiniPlayMode.setBackgroundResource(R.drawable.btn_primary_round)
            }
        }
    }

    private fun updateFavoriteIcon(songId: Long?) {
        val isFav = songId != null && PlaylistRepository.isFavorite(this, songId)
        btnPanelFavorite.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

    private fun toggleCurrentSongFavorite() {
        val song = musicService?.getCurrentSong() ?: return
        val isNowFavorite = PlaylistRepository.toggleFavorite(this, song.id)
        btnPanelFavorite.setImageResource(
            if (isNowFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        if (isPlaylistsTabActive) {
            loadPlaylists()
        }
    }

    // ---------- Panel de letra deslizable (estilo Spotify) ----------

    private fun setupLyricsPanel() {
        lyricsPanelBehavior = BottomSheetBehavior.from(lyricsPanel)
        lyricsPanelBehavior.isHideable = false
        lyricsPanelBehavior.skipCollapsed = false
        lyricsPanelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        // La lista de letras ocupa todo el espacio del panel siempre,
        // tanto colapsado como expandido; solo el encabezado (titulo +
        // boton de cerrar) aparece/desaparece segun el estado.
        rvLyricsPanel.alpha = 1f
        rvLyricsPanel.visibility = View.VISIBLE
        llLyricsHeader.alpha = 0f
        llLyricsHeader.visibility = View.INVISIBLE

        lyricsPanelBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        llLyricsHeader.alpha = 1f
                        llLyricsHeader.visibility = View.VISIBLE
                        lyricsAdapter?.let { scrollLyricsToLine(it.getActiveIndex()) }
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        llLyricsHeader.alpha = 0f
                        llLyricsHeader.visibility = View.INVISIBLE
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val progress = slideOffset.coerceIn(0f, 1f)
                llLyricsHeader.visibility = View.VISIBLE
                val fadeIn = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
                llLyricsHeader.alpha = fadeIn
            }
        })

        btnLyricsPanelClose.setOnClickListener {
            lyricsPanelBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    /**
     * Calcula el peek height del panel de letra para que, en reposo, ocupe
     * exactamente el espacio libre que queda debajo de llPanelControls
     * (justo debajo de los botones de reproducir/siguiente/anterior).
     */
    private fun updateLyricsPeekHeight() {
        groupExpanded.post {
            if (!::lyricsPanelBehavior.isInitialized) return@post
            if (groupExpanded.visibility != View.VISIBLE || groupExpanded.height == 0) return@post

            val controlsLocation = IntArray(2)
            llPanelControls.getLocationOnScreen(controlsLocation)
            val panelLocation = IntArray(2)
            groupExpanded.getLocationOnScreen(panelLocation)

            val controlsBottomOnScreen = controlsLocation[1] + llPanelControls.height
            val panelBottomOnScreen = panelLocation[1] + groupExpanded.height
            val freeSpace = panelBottomOnScreen - controlsBottomOnScreen

            val minPeek = (72 * resources.displayMetrics.density).toInt()
            val newPeek = freeSpace.coerceAtLeast(minPeek)
            if (newPeek != lyricsPanelBehavior.peekHeight) {
                lyricsPanelBehavior.peekHeight = newPeek
            }
        }
    }

    /**
     * Pide la letra sincronizada de la cancion actual y la deja lista en el
     * panel. Se ignora si la respuesta llega para una peticion vieja
     * (cancion ya cambiada) usando lyricsRequestId.
     */
    private fun loadLyricsForSong(song: Song) {
        if (lyricsSongId == song.id) return
        lyricsSongId = song.id
        lyricsRequestId++
        val requestId = lyricsRequestId

        showLyricsMessage("Buscando letra...")

        val durationSeconds = song.duration / 1000
        LyricsRepository.fetch(song.title, song.artist, durationSeconds, object : LyricsRepository.LyricsCallback {
            override fun onSuccess(result: LyricsResult) {
                if (requestId != lyricsRequestId) return
                when {
                    result.isInstrumental -> showLyricsMessage("Esta cancion es instrumental")
                    !result.syncedLines.isNullOrEmpty() -> showSyncedLyrics(result.syncedLines)
                    !result.plainLyrics.isNullOrBlank() -> showPlainLyrics(result.plainLyrics)
                    else -> showLyricsMessage("No se encontro letra para esta cancion")
                }
            }

            override fun onError(message: String) {
                if (requestId != lyricsRequestId) return
                showLyricsMessage(message)
            }
        })
    }

    private fun showLyricsMessage(message: String) {
        lyricsAdapter = null
        rvLyricsPanel.adapter = LyricsLineAdapter(listOf(LyricsLine(timeMs = -1, text = message)))
    }

    private fun showSyncedLyrics(lines: List<LyricsLine>) {
        val adapter = LyricsLineAdapter(lines)
        lyricsAdapter = adapter
        rvLyricsPanel.adapter = adapter
    }

    private fun showPlainLyrics(text: String) {
        val staticLines = text.lines()
            .filter { it.isNotBlank() }
            .map { LyricsLine(timeMs = -1, text = it) }
        val adapter = LyricsLineAdapter(staticLines)
        lyricsAdapter = adapter
        rvLyricsPanel.adapter = adapter
    }

    /**
     * Sincroniza la linea activa con la posicion de reproduccion actual.
     * Se llama desde miniProgressPoller. Siempre actualiza cual linea se
     * resalta en blanco brillante. El auto-scroll (seguir la letra) solo
     * ocurre cuando el panel esta colapsado (la franja de abajo); si el
     * usuario expandio el panel para leer la letra completa, esta se queda
     * estatica y el ya puede deslizarla libremente con el dedo.
     */
    private fun syncLyricsWithPosition(positionMs: Long) {
        val adapter = lyricsAdapter ?: return
        val previousIndex = adapter.getActiveIndex()
        val newIndex = adapter.updateActiveLine(positionMs)
        if (newIndex < 0) return

        // La linea vieja se atenua y la nueva se resalta con una transicion
        // suave (alpha + escala animados), en paralelo al reposicionamiento
        // del scroll, que sigue siendo instantaneo para no reintroducir el
        // desfase acumulado que tenia el smoothScroll continuo.
        adapter.animateActiveLineChange(rvLyricsPanel, previousIndex, newIndex)

        val isExpanded = ::lyricsPanelBehavior.isInitialized &&
                lyricsPanelBehavior.state == BottomSheetBehavior.STATE_EXPANDED
        if (!isExpanded) {
            scrollLyricsToLine(newIndex)
        }
    }

    /**
     * Pone la linea activa siempre pegada arriba del RecyclerView (nunca
     * centrada). Se pospone con post{} para que el smooth-scroll arranque
     * despues de que notifyItemChanged haya terminado su paso de layout; si
     * no, el scroll se calcula con el alto "viejo" de la linea y termina
     * pasandose de largo (el bug original de la pantalla de letras).
     *
     * Como la linea activa siempre queda al tope, cuando ya no quedan mas
     * lineas debajo (cerca del final de la cancion) el RecyclerView
     * simplemente no tiene mas contenido que subir: la letra se detiene
     * pegada arriba y el resto de la pantalla se queda vacio (en negro),
     * en vez de forzar ningun otro acomodo.
     */
    private fun scrollLyricsToLine(index: Int) {
        if (index < 0) return
        rvLyricsPanel.post {
            val layoutManager = rvLyricsPanel.layoutManager as? LinearLayoutManager ?: return@post
            val isExpanded = ::lyricsPanelBehavior.isInitialized &&
                    lyricsPanelBehavior.state == BottomSheetBehavior.STATE_EXPANDED

            if (isExpanded) {
                // Expandido: el RecyclerView ocupa toda la pantalla visible;
                // se desliza suavemente pero siempre queda pegada arriba,
                // igual que en la franja colapsada.
                rvLyricsPanel.stopScroll()
                val smoothScroller = object : LinearSmoothScroller(this) {
                    override fun getVerticalSnapPreference(): Int = SNAP_TO_START
                    override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
                        // Mas alto = mas lento/suave. El valor por defecto
                        // (25f/densityDpi) hace que distancias cortas se
                        // vean como un salto brusco; con esto se ve como
                        // un deslizamiento agradable.
                        return 70f / displayMetrics.densityDpi
                    }
                }
                smoothScroller.targetPosition = index
                layoutManager.startSmoothScroll(smoothScroller)
            } else {
                // Colapsado: solo se ve una franja pequena arriba del
                // RecyclerView (el peek del bottom sheet); el resto de la
                // vista sigue existiendo fuera de pantalla. Antes se usaba
                // un smoothScroll aqui tambien, pero si la linea cambiaba
                // antes de que la animacion anterior terminara (o mientras
                // el item animator seguia con su propia transicion), los
                // pequenos desfases se iban acumulando hasta que la linea
                // resaltada quedaba fuera de la franja visible. Por eso
                // aqui se detiene cualquier scroll en curso y se reubica de
                // forma exacta e inmediata en el tope cada vez: garantiza
                // que la linea en blanco brillante siempre sea visible,
                // sin depender de animaciones que se puedan interrumpir.
                rvLyricsPanel.stopScroll()
                layoutManager.scrollToPositionWithOffset(index, 0)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun startMiniProgressPolling() {
        uiHandler.removeCallbacks(miniProgressPoller)
        uiHandler.post(miniProgressPoller)
    }

    private fun stopMiniProgressPolling() {
        uiHandler.removeCallbacks(miniProgressPoller)
    }

    // ---------- Cola de reproduccion (bottom sheet) ----------

    private fun showQueueSheet() {
        val service = musicService ?: return
        if (service.getSongList().isEmpty()) return

        val dialog = BottomSheetDialog(this, R.style.RoundedBottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_queue, null)
        dialog.setContentView(view)
        activeQueueDialog = dialog

        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                it.requestLayout()
            }
        }

        queueTvTitle = view.findViewById(R.id.tvSheetSongTitle)
        queuePlayPauseBtn = view.findViewById(R.id.btnSheetPlayPause)
        queueProgressBar = view.findViewById(R.id.pbSheetProgress)
        queueRecyclerView = view.findViewById(R.id.rvQueue)
        queueBtnModeNormal = view.findViewById(R.id.btnModeNormal)
        queueBtnModeRepeat = view.findViewById(R.id.btnModeRepeat)
        queueBtnModeShuffle = view.findViewById(R.id.btnModeShuffle)

        val layoutManager = LinearLayoutManager(this)
        queueRecyclerView?.layoutManager = layoutManager

        refreshQueueList()
        refreshQueueHeader()
        refreshModeButtons()

        queuePlayPauseBtn?.setOnClickListener {
            musicService?.togglePlayPause()
        }

        val btnLocateCurrent: ImageButton = view.findViewById(R.id.btnLocateCurrent)
        btnLocateCurrent.setOnClickListener {
            layoutManager.scrollToPositionWithOffset(musicService?.getCurrentIndex() ?: 0, 0)
        }

        queueBtnModeNormal?.setOnClickListener {
            musicService?.setPlaybackMode(MusicService.PlaybackMode.NORMAL)
            refreshModeButtons()
            refreshQueueList()
        }

        queueBtnModeRepeat?.setOnClickListener {
            musicService?.setPlaybackMode(MusicService.PlaybackMode.REPEAT_ONE)
            refreshModeButtons()
            refreshQueueList()
        }

        queueBtnModeShuffle?.setOnClickListener {
            musicService?.setPlaybackMode(MusicService.PlaybackMode.SHUFFLE)
            refreshModeButtons()
            refreshQueueList()
        }

        dialog.setOnDismissListener {
            queueTvTitle = null
            queuePlayPauseBtn = null
            queueProgressBar = null
            queueRecyclerView = null
            queueBtnModeNormal = null
            queueBtnModeRepeat = null
            queueBtnModeShuffle = null
            queueTouchHelper = null
            activeQueueDialog = null
        }

        dialog.show()
    }

    private fun refreshQueueList() {
        val service = musicService ?: return
        val adapter = QueueAdapter(
            service.getSongList().toMutableList(),
            service.getCurrentIndex(),
            onItemClick = { position ->
                musicService?.playAt(position)
                activeQueueDialog?.dismiss()
            },
            onMoveFinished = { from, to ->
                musicService?.moveQueueItem(from, to)
            }
        )
        queueRecyclerView?.adapter = adapter

        val touchHelper = ItemTouchHelper(
            QueueTouchHelperCallback(adapter) { position ->
                val current = service.getCurrentIndex()
                val target = if (position > current) current + 1 else current
                musicService?.moveQueueItem(position, target)
                Toast.makeText(this, "Sonará a continuación", Toast.LENGTH_SHORT).show()
                refreshQueueList()
            }
        )
        touchHelper.attachToRecyclerView(queueRecyclerView)
        queueTouchHelper = touchHelper
        adapter.dragStartListener = { viewHolder -> touchHelper.startDrag(viewHolder) }
    }

    private fun refreshQueueHeader() {
        val service = musicService ?: return
        val currentSong = service.getCurrentSong()
        queueTvTitle?.text = currentSong?.title ?: ""
        queuePlayPauseBtn?.setImageResource(
            if (service.isPlaying()) R.drawable.ic_pause_small else R.drawable.ic_play_small
        )
        queueProgressBar?.max = service.getDuration()
        queueProgressBar?.progress = service.getCurrentPosition()
    }

    private fun refreshModeButtons() {
        val currentMode = musicService?.getPlaybackMode() ?: MusicService.PlaybackMode.NORMAL
        queueBtnModeNormal?.setBackgroundResource(
            if (currentMode == MusicService.PlaybackMode.NORMAL) R.drawable.bg_mode_pill_active else 0
        )
        queueBtnModeRepeat?.setBackgroundResource(
            if (currentMode == MusicService.PlaybackMode.REPEAT_ONE) R.drawable.bg_mode_pill_active else 0
        )
        queueBtnModeShuffle?.setBackgroundResource(
            if (currentMode == MusicService.PlaybackMode.SHUFFLE) R.drawable.bg_mode_pill_active else 0
        )
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
        allSongs = SongRepository.getAllSongs(this)

        if (allSongs.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
            rvSongs.visibility = View.GONE
            return
        }

        applyFilterAndSort()
    }

    private fun applyFilterAndSort() {
        if (isPlaylistsTabActive) return

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
            SortType.MOST_PLAYED -> list.sortedByDescending { PlayCountRepository.getPlayCount(this, it.id) }
        }

        songAdapter.updateData(list)

        val hasResults = list.isNotEmpty()
        tvEmptyState.visibility = if (hasResults) View.GONE else View.VISIBLE
        if (!isPlaylistsTabActive) {
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

    /**
     * Arma las playlists automaticas de historial ("Recientes" y "Mas
     * escuchadas") a partir de PlayCountRepository. No se guardan en disco:
     * se recalculan cada vez que se abre la pestana de playlists. Solo se
     * muestran si ya hay al menos una reproduccion registrada.
     */
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

    private fun showCreatePlaylistDialog(songIdToAdd: Long?) {
        val input = EditText(this)
        input.hint = "Nombre de la playlist"
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary_light))
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary_light))

        AlertDialog.Builder(this, R.style.RoundedAlertDialog)
            .setTitle("Nueva playlist")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val playlist = PlaylistRepository.createPlaylist(this, name)
                    if (songIdToAdd != null) {
                        PlaylistRepository.addSongToPlaylist(this, playlist.id, songIdToAdd)
                        Toast.makeText(this, "Cancion agregada a \"$name\"", Toast.LENGTH_SHORT).show()
                    }
                    if (isPlaylistsTabActive) {
                        loadPlaylists()
                    }
                } else {
                    Toast.makeText(this, "El nombre no puede estar vacio", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val playlists = PlaylistRepository.getAllPlaylists(this)
        val options = playlists.map { it.name }.toMutableList()
        options.add("+ Crear nueva playlist")

        AlertDialog.Builder(this, R.style.RoundedAlertDialog)
            .setTitle("Agregar a playlist")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == playlists.size) {
                    showCreatePlaylistDialog(song.id)
                } else {
                    val playlist = playlists[which]
                    PlaylistRepository.addSongToPlaylist(this, playlist.id, song.id)
                    Toast.makeText(
                        this,
                        "Agregada a \"${playlist.name}\"",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun requestCoverImage(playlist: Playlist) {
        pendingCoverPlaylistId = playlist.id
        pickCoverLauncher.launch("image/*")
    }

    private fun confirmDeletePlaylist(playlist: Playlist) {
        AlertDialog.Builder(this, R.style.RoundedAlertDialog)
            .setTitle("Eliminar playlist")
            .setMessage("Eliminar \"${playlist.name}\"? Esta accion no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                PlaylistRepository.deletePlaylist(this, playlist.id)
                loadPlaylists()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openPlaylistDetail(playlist: Playlist) {
        val intent = Intent(this, PlaylistDetailActivity::class.java)
        intent.putExtra("playlist_id", playlist.id)
        startActivity(intent)
    }

    private fun openPlayer(playlist: List<Song>, startIndex: Int) {
        val service = musicService
        if (service != null) {
            service.setPlaylist(playlist, startIndex)
            expandPlayerPanelWhenReady()
        } else {
            pendingSongList = playlist
            pendingStartIndex = startIndex
        }
    }

    private fun showMiniPlayer(song: Song, playing: Boolean) {
        playerPanel.visibility = View.VISIBLE

        tvMiniTitle.text = song.title
        tvMiniArtist.text = song.artist
        tvPanelSongTitle.text = song.title
        tvPanelArtist.text = song.artist
        sbPanelProgress.setWaveformSeed(song.id)

        btnMiniPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause_small else R.drawable.ic_play_small
        )
        btnPanelPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )

        updateFavoriteIcon(song.id)
        updatePeekHeight()
        updateLyricsPeekHeight()
        loadLyricsForSong(song)
    }

    override fun onSongChanged(song: Song, index: Int) {
        runOnUiThread {
            showMiniPlayer(song, musicService?.isPlaying() == true)
            songAdapter.setCurrentPlayingId(song.id)
            queueTvTitle?.text = song.title
            if (activeQueueDialog != null) {
                refreshQueueList()
            }
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            btnMiniPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause_small else R.drawable.ic_play_small
            )
            btnPanelPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
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
        musicService?.let { updateModeButtonIcon(it.getPlaybackMode()) }
        startMiniProgressPolling()

        if (isPlaylistsTabActive) {
            loadPlaylists()
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
        activeQueueDialog?.dismiss()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}