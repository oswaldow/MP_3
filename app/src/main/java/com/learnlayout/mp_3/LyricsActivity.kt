package com.learnlayout.mp_3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

class LyricsActivity : AppCompatActivity() {

    private lateinit var rootLayout: View
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvMessage: TextView
    private lateinit var rvLyrics: RecyclerView

    // ---------- Sincronizacion manual ----------
    private lateinit var btnLyricsEdit: ImageButton
    private lateinit var btnLyricsSyncToggle: ImageButton
    private lateinit var tvSyncHint: TextView
    private lateinit var lyricsEditOverlay: LinearLayout
    private lateinit var etLyricsRaw: EditText
    private lateinit var btnConfirmEditLyrics: TextView
    private lateinit var llSyncControls: LinearLayout
    private lateinit var btnSyncPlayPause: ImageButton
    private lateinit var tvSyncProgress: TextView
    private lateinit var btnSaveSync: TextView

    private var isSyncMode = false
    private var originalRvLyricsPaddingTop = 0
    private var originalRvLyricsPaddingBottom = 0

    // Texto de las lineas actualmente mostradas (sin tiempos), usado como
    // base cuando se entra a modo sincronizacion.
    private var currentLines: MutableList<String> = mutableListOf()
    // Timestamp manual capturado por linea (mismo tamano que currentLines).
    // null = todavia no se ha tocado esa linea.
    private var manualTimes: MutableList<Long?> = mutableListOf()

    // Ultima letra cargada normalmente (LRCLIB o guardada), para poder
    // prellenar el editor / arrancar la sincronizacion sin volver a pedirla.
    private var lastLoadedSyncedLines: List<LyricsLine>? = null
    private var lastLoadedPlainText: String? = null

    private var musicService: MusicService? = null
    private var isBound = false

    private var song: Song? = null
    private var lyricsAdapter: LyricsLineAdapter? = null

    // ---------- Tema dinamico (Material You / PlayerPaletteTheme) ----------
    // Mismo criterio que EqualizerActivity y el panel del reproductor: el
    // fondo de toda la pantalla se oscurece segun la caratula de la
    // cancion, y los botones que antes eran spotify_green fijo (usar letra
    // y sincronizar / guardar sincronizacion) pasan a seguir ese acento.
    // La linea activa de la letra (item_lyrics_line) NO se toca aqui: su
    // color ya se ajusto en el punto 1 para mantener contraste con fondos
    // claros, y cambiarlo a un acento dinamico podria romper eso de nuevo.
    // Como el Song ya llega por Intent, no hace falta esperar al servicio
    // para tematizar: se carga apenas se conoce la cancion.
    private val defaultBannerColor: Int by lazy { ContextCompat.getColor(this, R.color.background_dark) }
    private val defaultAccentColor: Int by lazy { ContextCompat.getColor(this, R.color.spotify_green) }
    private var currentAccentColor: Int = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            val service = musicService
            if (service != null) {
                if (!isSyncMode) {
                    val adapter = lyricsAdapter
                    if (adapter != null) {
                        val newIndex = adapter.updateActiveLine(service.getCurrentPosition().toLong())
                        if (newIndex >= 0) scrollToLine(newIndex)
                    }
                } else {
                    updateSyncPlayPauseIcon(service.isPlaying())
                }
            }
            uiHandler.postDelayed(this, 400)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.MusicBinder).getService()
            isBound = true
            uiHandler.post(syncRunnable)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics)

        currentAccentColor = defaultAccentColor

        rootLayout = findViewById(R.id.rootLyricsLayout)
        btnBack = findViewById(R.id.btnLyricsBack)
        tvTitle = findViewById(R.id.tvLyricsTitle)
        progressBar = findViewById(R.id.progressLyrics)
        tvMessage = findViewById(R.id.tvLyricsMessage)
        rvLyrics = findViewById(R.id.rvLyrics)

        btnLyricsEdit = findViewById(R.id.btnLyricsEdit)
        btnLyricsSyncToggle = findViewById(R.id.btnLyricsSyncToggle)
        tvSyncHint = findViewById(R.id.tvSyncHint)
        lyricsEditOverlay = findViewById(R.id.lyricsEditOverlay)
        etLyricsRaw = findViewById(R.id.etLyricsRaw)
        btnConfirmEditLyrics = findViewById(R.id.btnConfirmEditLyrics)
        llSyncControls = findViewById(R.id.llSyncControls)
        btnSyncPlayPause = findViewById(R.id.btnSyncPlayPause)
        tvSyncProgress = findViewById(R.id.tvSyncProgress)
        btnSaveSync = findViewById(R.id.btnSaveSync)

        rvLyrics.layoutManager = LinearLayoutManager(this)
        rvLyrics.itemAnimator = null
        originalRvLyricsPaddingTop = rvLyrics.paddingTop
        originalRvLyricsPaddingBottom = rvLyrics.paddingBottom

        btnBack.setOnClickListener { finish() }

        btnLyricsEdit.setOnClickListener {
            val prefill = when {
                currentLines.isNotEmpty() -> currentLines.joinToString("\n")
                lastLoadedSyncedLines != null -> lastLoadedSyncedLines!!.joinToString("\n") { it.text }
                lastLoadedPlainText != null -> lastLoadedPlainText!!
                else -> ""
            }
            etLyricsRaw.setText(prefill)
            lyricsEditOverlay.visibility = View.VISIBLE
        }

        btnConfirmEditLyrics.setOnClickListener {
            val lines = etLyricsRaw.text.toString().lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                Toast.makeText(this, "Escribe al menos una linea", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lyricsEditOverlay.visibility = View.GONE
            beginSyncWithLines(lines)
        }

        btnLyricsSyncToggle.setOnClickListener {
            if (isSyncMode) {
                cancelSyncMode()
            } else {
                startManualSyncMode()
            }
        }

        btnSyncPlayPause.setOnClickListener {
            musicService?.togglePlayPause()
        }

        btnSaveSync.setOnClickListener {
            saveManualSync()
        }

        song = intent.getParcelableExtra("song")
        tvTitle.text = song?.title ?: "Letra"

        val intentSong = song
        if (intentSong == null) {
            showMessage("No se pudo identificar la cancion")
            return
        }

        applyThemeFallback()
        loadAlbumArtTheme(intentSong)

        bindService(Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)

        loadLyrics(intentSong)
    }

    private fun loadLyrics(song: Song) {
        showLoading()
        val saved = SavedLyricsRepository.getSavedLyrics(this, song.id)
        if (saved != null) {
            applyLoadedResult(saved)
            return
        }
        val durationSeconds = song.duration / 1000
        LyricsRepository.fetch(song.title, song.artist, durationSeconds, object : LyricsRepository.LyricsCallback {
            override fun onSuccess(result: LyricsResult) {
                applyLoadedResult(result)
            }

            override fun onError(message: String) {
                showMessage(message)
            }
        })
    }

    private fun applyLoadedResult(result: LyricsResult) {
        when {
            result.isInstrumental -> showMessage("Esta cancion es instrumental")
            !result.syncedLines.isNullOrEmpty() -> showSynced(result.syncedLines)
            !result.plainLyrics.isNullOrBlank() -> showPlain(result.plainLyrics)
            else -> showMessage("No se encontro letra para esta cancion")
        }
    }

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        tvMessage.visibility = View.GONE
        rvLyrics.visibility = View.GONE
    }

    private fun showMessage(message: String) {
        progressBar.visibility = View.GONE
        rvLyrics.visibility = View.GONE
        tvMessage.visibility = View.VISIBLE
        tvMessage.text = message
        lastLoadedSyncedLines = null
        lastLoadedPlainText = null
        updateManualSyncButtonsVisibility()
    }

    private fun showSynced(lines: List<LyricsLine>) {
        progressBar.visibility = View.GONE
        tvMessage.visibility = View.GONE
        rvLyrics.visibility = View.VISIBLE

        val adapter = LyricsLineAdapter(lines)
        lyricsAdapter = adapter
        rvLyrics.adapter = adapter

        lastLoadedSyncedLines = lines
        lastLoadedPlainText = null
        updateManualSyncButtonsVisibility()
    }

    private fun showPlain(text: String) {
        progressBar.visibility = View.GONE
        rvLyrics.visibility = View.VISIBLE

        val staticLines = text.lines()
            .filter { it.isNotBlank() }
            .map { LyricsLine(timeMs = -1, text = it) }
        rvLyrics.adapter = LyricsLineAdapter(staticLines)
        lyricsAdapter = null

        lastLoadedPlainText = text
        lastLoadedSyncedLines = null
        updateManualSyncButtonsVisibility()
    }

    // ==================== MODO DE SINCRONIZACION MANUAL ====================

    private fun startManualSyncMode() {
        val baseLines: List<String> = when {
            lastLoadedSyncedLines != null -> lastLoadedSyncedLines!!.map { it.text }
            lastLoadedPlainText != null -> lastLoadedPlainText!!.lines().filter { it.isNotBlank() }
            else -> emptyList()
        }
        if (baseLines.isEmpty()) {
            etLyricsRaw.setText("")
            lyricsEditOverlay.visibility = View.VISIBLE
            Toast.makeText(this, "Primero pega la letra de la cancion", Toast.LENGTH_SHORT).show()
            return
        }
        beginSyncWithLines(baseLines)
    }

    private fun beginSyncWithLines(lines: List<String>) {
        currentLines = lines.toMutableList()
        manualTimes = MutableList(currentLines.size) { null }
        isSyncMode = true
        updateManualSyncButtonsVisibility()

        val adapterLines = currentLines.map { LyricsLine(timeMs = -1, text = it) }
        val adapter = LyricsLineAdapter(adapterLines) { tappedIndex -> onLineTapped(tappedIndex) }
        lyricsAdapter = adapter
        rvLyrics.adapter = adapter

        progressBar.visibility = View.GONE
        tvMessage.visibility = View.GONE
        rvLyrics.visibility = View.VISIBLE
        tvSyncHint.visibility = View.VISIBLE
        llSyncControls.visibility = View.VISIBLE
        btnLyricsSyncToggle.setImageResource(R.drawable.ic_close)

        val density = resources.displayMetrics.density
        val extraBottomPaddingPx = (90 * density).toInt()
        // Padding inferior fijo para que los controles de abajo (play/progreso/guardar)
        // no tapen las ultimas lineas.
        rvLyrics.setPadding(
            rvLyrics.paddingLeft,
            rvLyrics.paddingTop,
            rvLyrics.paddingRight,
            originalRvLyricsPaddingBottom + extraBottomPaddingPx
        )

        // El banner "Toca la linea cuando empiece a sonar" flota encima del
        // RecyclerView. Le damos padding superior igual a su altura real
        // (medida despues del layout) + un margen, para que la primera
        // linea de la letra nunca quede tapada por el banner.
        tvSyncHint.post {
            val hintExtraPx = (12 * density).toInt()
            val topPadding = originalRvLyricsPaddingTop + tvSyncHint.height + hintExtraPx
            rvLyrics.setPadding(
                rvLyrics.paddingLeft,
                topPadding,
                rvLyrics.paddingRight,
                rvLyrics.paddingBottom
            )
        }

        adapter.setActiveIndexDirect(0)
        updateSyncProgressUI()
        musicService?.let { updateSyncPlayPauseIcon(it.isPlaying()) }
    }

    private fun onLineTapped(index: Int) {
        val service = musicService ?: run {
            Toast.makeText(this, "Reproductor no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        manualTimes[index] = service.getCurrentPosition().toLong()
        val adapter = lyricsAdapter ?: return
        adapter.markTagged(index)

        // La siguiente linea "activa" es la primera pendiente (permite
        // corregir lineas anteriores sin perder el hilo de las que faltan).
        val nextPending = manualTimes.indexOfFirst { it == null }
        adapter.setActiveIndexDirect(if (nextPending == -1) -1 else nextPending)

        // Solo hacemos scroll automatico hacia ABAJO, cuando la siguiente
        // linea pendiente esta fuera de la vista actual. Nunca hacia arriba:
        // si tocaste una linea mas adelante sin marcar una anterior, esa
        // anterior ya esta visible (arriba de donde estas), asi que no hay
        // que saltar hacia ella.
        if (nextPending != -1) {
            val layoutManager = rvLyrics.layoutManager as? LinearLayoutManager
            val lastVisible = layoutManager?.findLastCompletelyVisibleItemPosition() ?: -1
            if (nextPending > lastVisible) {
                scrollToLine(nextPending)
            }
        }

        updateSyncProgressUI()
    }

    private fun updateSyncProgressUI() {
        val total = currentLines.size
        val tagged = manualTimes.count { it != null }
        tvSyncProgress.text = "$tagged/$total lineas"
        val allTagged = total > 0 && tagged == total
        btnSaveSync.isEnabled = allTagged
        btnSaveSync.alpha = if (allTagged) 1f else 0.5f
    }

    private fun updateManualSyncButtonsVisibility() {
        if (isSyncMode) {
            // Mientras se esta sincronizando, el boton de mira se necesita
            // para poder cancelar (queda como "X").
            btnLyricsSyncToggle.visibility = View.VISIBLE
            btnLyricsEdit.visibility = View.GONE
            return
        }
        val hasOwnTimer = lastLoadedSyncedLines != null
        // El boton de sincronizar (mira) solo tiene sentido si NO hay timestamps
        // propios todavia. El boton de editar, en cambio, siempre debe estar
        // disponible: el usuario puede querer corregir la letra aunque ya
        // tenga tiempos sincronizados.
        btnLyricsSyncToggle.visibility = if (hasOwnTimer) View.GONE else View.VISIBLE
        btnLyricsEdit.visibility = View.VISIBLE
    }

    private fun updateSyncPlayPauseIcon(isPlaying: Boolean) {
        btnSyncPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause_small else R.drawable.ic_play_small)
    }

    private fun resetRvLyricsPadding() {
        rvLyrics.setPadding(
            rvLyrics.paddingLeft,
            originalRvLyricsPaddingTop,
            rvLyrics.paddingRight,
            originalRvLyricsPaddingBottom
        )
    }

    private fun cancelSyncMode() {
        isSyncMode = false
        tvSyncHint.visibility = View.GONE
        llSyncControls.visibility = View.GONE
        btnLyricsSyncToggle.setImageResource(R.drawable.ic_crosshair)
        resetRvLyricsPadding()
        song?.let { loadLyrics(it) }
        updateManualSyncButtonsVisibility()
    }

    private fun saveManualSync() {
        val currentSong = song ?: return
        if (manualTimes.any { it == null } || currentLines.isEmpty()) {
            Toast.makeText(this, "Aun faltan lineas por sincronizar", Toast.LENGTH_SHORT).show()
            return
        }

        val lines = currentLines.indices.map { i -> LyricsLine(timeMs = manualTimes[i]!!, text = currentLines[i]) }
        val result = LyricsResult(
            plainLyrics = currentLines.joinToString("\n"),
            syncedLines = lines,
            isInstrumental = false
        )
        SavedLyricsRepository.save(this, currentSong.id, result)
        Toast.makeText(this, "Sincronizacion guardada", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)

        isSyncMode = false
        tvSyncHint.visibility = View.GONE
        llSyncControls.visibility = View.GONE
        btnLyricsSyncToggle.setImageResource(R.drawable.ic_crosshair)
        resetRvLyricsPadding()
        showSynced(lines)
        updateManualSyncButtonsVisibility()
    }

    // ==================== FIN MODO DE SINCRONIZACION MANUAL ====================

    // ---------- Tema dinamico: carga y aplicacion ----------

    private fun loadAlbumArtTheme(song: Song) {
        AlbumArtRepository.loadCover(this, song, object : AlbumArtRepository.Callback {
            override fun onCoverReady(bitmap: Bitmap) {
                applyThemeFromBitmap(bitmap)
            }
        })
        // Si no hay caratula en cache ni en red, loadCover no llama al
        // callback: la pantalla se queda con el fallback ya aplicado en
        // onCreate (fondo background_dark, acento spotify_green).
    }

    private fun applyThemeFromBitmap(bitmap: Bitmap) {
        PlayerPaletteTheme.applyFromBitmap(bitmap, rootLayout, defaultBannerColor)
        PlayerPaletteTheme.applyAccentFromBitmap(
            bitmap, defaultAccentColor, currentAccentColor
        ) { color ->
            currentAccentColor = color
            applyAccentToControls(color)
        }
    }

    private fun applyThemeFallback() {
        PlayerPaletteTheme.applyFallback(rootLayout, defaultBannerColor)
        PlayerPaletteTheme.applyAccentFallback(defaultAccentColor, currentAccentColor) { color ->
            currentAccentColor = color
            applyAccentToControls(color)
        }
    }

    private fun applyAccentToControls(color: Int) {
        val accentTint = ColorStateList.valueOf(color)
        val onColor = PlayerPaletteTheme.onColorFor(color)
        btnConfirmEditLyrics.backgroundTintList = accentTint
        btnConfirmEditLyrics.setTextColor(onColor)
        btnSaveSync.backgroundTintList = accentTint
        btnSaveSync.setTextColor(onColor)
    }

    private fun scrollToLine(index: Int) {
        rvLyrics.post {
            val layoutManager = rvLyrics.layoutManager as? LinearLayoutManager ?: return@post
            val smoothScroller = object : LinearSmoothScroller(this) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_START
            }
            smoothScroller.targetPosition = index
            layoutManager.startSmoothScroll(smoothScroller)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(syncRunnable)
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}