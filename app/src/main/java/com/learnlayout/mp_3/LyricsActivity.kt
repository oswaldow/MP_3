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
import android.util.Log
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

    // ---------- Fondo dinamico de toda la pantalla (Material You + destellos) ----------
    // Antes esta pantalla pintaba su fondo con un solo color plano
    // (PlayerPaletteTheme.applyFromBitmap/applyFallback sobre rootLayout).
    // Ahora usa el mismo AmbientBackgroundController que el Home: degradado
    // de 3 tonos + destellos animados con el color vivo de la caratula. El
    // acento de los botones (usar letra / guardar sincronizacion) sigue
    // usando PlayerPaletteTheme por separado, eso no cambia.
    private val ambientBackground: AmbientBackgroundController by lazy {
        AmbientBackgroundController(this, rootLayout)
    }

    // ---------- Tema dinamico (Material You / PlayerPaletteTheme) ----------
    // Mismo criterio que el resto de la app: los botones que antes eran
    // spotify_green fijo (usar letra y sincronizar / guardar sincronizacion)
    // siguen el acento vivo de la caratula. La linea activa de la letra
    // (item_lyrics_line) NO se toca aqui: su color ya se ajusto en el punto
    // 1 para mantener contraste con fondos claros, y cambiarlo a un acento
    // dinamico podria romper eso de nuevo.
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

        // tvSyncHint y llSyncControls flotan encima de rvLyrics (ver
        // applySyncModePadding), pero el padding por si solo solo evita
        // que las lineas se DIBUJEN detras de ellos: no cambia que
        // rvLyrics siga siendo, para efectos de deteccion de toques, la
        // misma vista de siempre ocupando toda la pantalla. Si en el
        // layout quedo declarado despues de estos dos, se queda con el
        // toque antes de que le llegue al boton "Guardar" -exactamente
        // el reporte de "presiono Guardar y no pasa nada"-, sin importar
        // el padding. Se traen al frente para que ganen esa prioridad de
        // toque sobre la lista, de una vez y para siempre (no hace falta
        // repetirlo cada vez que se muestran/ocultan).
        tvSyncHint.bringToFront()
        llSyncControls.bringToFront()

        btnBack.setOnClickListener { finish() }

        btnLyricsEdit.setOnClickListener {
            val prefill = if (currentLines.isNotEmpty()) {
                currentLines.joinToString("\n")
            } else {
                lastLoadedSyncedLines?.joinToString("\n") { it.text }
                    ?: lastLoadedPlainText
                    ?: ""
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
            // DEBUG temporal (ver TAG_SYNCSAVE): dejar hasta confirmar en
            // un dispositivo real que el boton ya dispara el click ahora
            // que se habilita en updateSaveSyncButtonEnabled(). Se puede
            // quitar junto con el resto de logs "DEBUG temporal".
            Log.d(TAG_SYNCSAVE, "btnSaveSync.onClick recibido (isSyncMode=$isSyncMode)")
            try {
                saveManualSync()
            } catch (e: Exception) {
                Log.e(TAG_SYNCSAVE, "btnSaveSync.onClick: excepcion dentro de saveManualSync()", e)
            }
        }

        song = intent.getParcelableExtra("song")
        tvTitle.text = song?.title ?: "Letra"

        val intentSong = song
        if (intentSong == null) {
            showMessage("No se pudo identificar la cancion")
            return
        }

        applyThemeFallback()
        ambientBackground.updateForSong(intentSong)
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
                val hasContent = !result.isInstrumental &&
                        (!result.syncedLines.isNullOrEmpty() || !result.plainLyrics.isNullOrBlank())
                if (hasContent) {
                    // Antes esta letra recien encontrada solo se mostraba en
                    // pantalla y nunca se guardaba: cada vez que se volvia a
                    // abrir esta cancion se volvia a pedir a LRCLIB desde
                    // cero. Ahora se guarda igual que cualquier otra letra
                    // (cache de la app + archivo real, ver
                    // persistLyricsToAudioFileIfPossible).
                    SavedLyricsRepository.save(this@LyricsActivity, song.id, result)
                    persistLyricsToAudioFileIfPossible(song, result)
                }
                applyLoadedResult(result)
            }

            override fun onError(message: String) {
                showMessage(message)
            }
        })
    }

    /**
     * Si la app ya tiene el permiso de "Todos los archivos", graba [result]
     * directo dentro del archivo de audio de [song], ademas del guardado
     * normal en SavedLyricsRepository, para que la letra sobreviva a una
     * desinstalacion sin que el usuario tenga que entrar a Ajustes >
     * Letras y Caratulas a forzar la actualizacion. Se hace en un hilo de
     * fondo (jaudiotagger hace I/O bloqueante) y nunca muestra ningun
     * error: es un guardado de "mejor esfuerzo".
     */
    private fun persistLyricsToAudioFileIfPossible(song: Song, result: LyricsResult) {
        val hasPermission = SongFileTagWriter.hasManageStoragePermission(this)
        // DEBUG temporal (ver TAG_SYNCSAVE)
        Log.d(TAG_SYNCSAVE, "persistLyricsToAudioFileIfPossible(): hasManageStoragePermission=$hasPermission")
        if (!hasPermission) return
        val appContext = applicationContext
        AppExecutors.runInBackground {
            Log.d(TAG_SYNCSAVE, "persistLyricsToAudioFileIfPossible(): escribiendo en archivo (hilo de fondo)")
            try {
                val ok = SongFileTagWriter.writeToFile(appContext, song, lyricsResult = result)
                Log.d(TAG_SYNCSAVE, "persistLyricsToAudioFileIfPossible(): writeToFile() devolvio $ok")
            } catch (e: Exception) {
                // Antes esta excepcion (si la hubiera) se hubiera perdido
                // en el hilo de fondo sin ningun rastro en logcat.
                Log.e(TAG_SYNCSAVE, "persistLyricsToAudioFileIfPossible(): excepcion escribiendo en archivo", e)
            }
        }
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
        val baseLines: List<String> = lastLoadedSyncedLines?.map { it.text }
            ?: lastLoadedPlainText?.lines()?.filter { it.isNotBlank() }
            ?: emptyList()
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
        updateSaveSyncButtonEnabled()

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
        updateSyncProgressLabel()
        applySyncModePadding()
    }

    /**
     * FIX (ver TAG_SYNCSAVE): btnSaveSync nace con android:enabled="false"
     * en el XML (por eso se ve "apagado" con alpha=0.5 hasta que terminas
     * de sincronizar), pero en ningun lado del codigo se volvia a poner
     * isEnabled=true. Una vista deshabilitada sigue "quedandose" con el
     * toque (por eso no pasaba nada ni aparecia ningun otro mensaje) pero
     * nunca llega a disparar performClick(), asi que el listener de
     * "Guardar" jamas se ejecutaba, sin importar cuantas lineas tuvieras
     * sincronizadas. Ahora el boton se habilita/deshabilita en vivo segun
     * si ya se toco cada linea.
     */
    private fun updateSaveSyncButtonEnabled() {
        val allTagged = currentLines.isNotEmpty() && manualTimes.all { it != null }
        btnSaveSync.isEnabled = allTagged
        btnSaveSync.alpha = if (allTagged) 1f else 0.5f
    }

    /**
     * El banner "Toca la linea cuando empiece a sonar" (tvSyncHint) y la
     * barra inferior (llSyncControls: play/pausa, contador y "Guardar")
     * quedan flotando encima de la lista de letra, y como antes no se
     * les hacia espacio, tapaban las primeras Y ultimas lineas apenas se
     * entraba a modo sincronizacion.
     *
     * Que el banner tape lineas de arriba es solo un problema visual,
     * pero que la barra de abajo quede tapada por lineas de la lista es
     * ademas un problema de toques: al no tener padding inferior,
     * rvLyrics se extendia hasta el fondo de la pantalla, y como queda
     * por encima de llSyncControls en el layout, se quedaba con los
     * toques que el usuario le daba al boton "Guardar" en esa zona (se
     * presionaba el boton y no pasaba absolutamente nada, ni siquiera el
     * aviso de "aun faltan lineas").
     *
     * Se miden las alturas reales de ambas vistas (ya con contenido
     * puesto, por eso se hace en post{}, tras el siguiente paso de
     * layout) y se usan como padding superior/inferior de rvLyrics -mas
     * un margen chico en cada extremo- para que ninguna linea quede
     * detras de ellas.
     */
    private fun applySyncModePadding() {
        tvSyncHint.post {
            val topGap = (SYNC_HINT_GAP_DP * resources.displayMetrics.density).toInt()
            val bottomGap = (SYNC_CONTROLS_GAP_DP * resources.displayMetrics.density).toInt()

            rvLyrics.setPadding(
                rvLyrics.paddingLeft,
                tvSyncHint.bottom + topGap,
                rvLyrics.paddingRight,
                llSyncControls.height + bottomGap
            )
        }
    }

    private fun onLineTapped(index: Int) {
        val service = musicService ?: return
        manualTimes[index] = service.getCurrentPosition().toLong()
        // El metodo del adapter se llama "markTagged" (le pone el check a
        // la linea), no "markLineSynced".
        (lyricsAdapter as? LyricsLineAdapter)?.markTagged(index)
        updateSyncProgressLabel()

        // Antes, al tocar una linea, la pantalla bajaba sola a la
        // siguiente (scrollToLine(nextIndex)). Eso interfiere con quien
        // sincroniza a su propio ritmo: el usuario pidio que el scroll lo
        // controle el solo con el dedo, asi que ya no se mueve nada aca.
    }

    private fun updateSyncProgressLabel() {
        val done = manualTimes.count { it != null }
        tvSyncProgress.text = "$done/${currentLines.size}"
        updateSaveSyncButtonEnabled()
    }

    private fun updateSyncPlayPauseIcon(isPlaying: Boolean) {
        btnSyncPlayPause.setImageResource(
            // No existe R.drawable.ic_play entre tus drawables; el que
            // tienes es ic_play_arrow (el mismo que usa el reproductor).
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    private fun updateManualSyncButtonsVisibility() {
        val hasContent = lastLoadedSyncedLines != null || !lastLoadedPlainText.isNullOrBlank()
        btnLyricsSyncToggle.visibility = if (isSyncMode || hasContent) View.VISIBLE else View.GONE
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
        // DEBUG temporal (ver TAG_SYNCSAVE)
        Log.d(
            TAG_SYNCSAVE,
            "saveManualSync() inicio: currentLines.size=${currentLines.size} " +
                    "manualTimes.size=${manualTimes.size} nulos=${manualTimes.count { it == null }}"
        )

        val currentSong = song
        if (currentSong == null) {
            Log.w(TAG_SYNCSAVE, "saveManualSync(): song es null, se aborta sin avisar al usuario")
            return
        }
        if (manualTimes.any { it == null } || currentLines.isEmpty()) {
            Log.w(TAG_SYNCSAVE, "saveManualSync(): validacion fallo (hay nulos o currentLines vacio)")
            Toast.makeText(this, "Aun faltan lineas por sincronizar", Toast.LENGTH_SHORT).show()
            return
        }

        val lines = currentLines.indices.map { i ->
            val timeMs = requireNotNull(manualTimes[i]) {
                "manualTimes[$i] es null pese a la validacion previa"
            }
            LyricsLine(timeMs = timeMs, text = currentLines[i])
        }
        val result = LyricsResult(
            plainLyrics = currentLines.joinToString("\n"),
            syncedLines = lines,
            isInstrumental = false
        )
        Log.d(
            TAG_SYNCSAVE,
            "saveManualSync(): validacion OK, guardando ${lines.size} lineas para songId=${currentSong.id}"
        )

        // Sincronizacion manual: se guarda como eleccion manual para que
        // no se pise sola con la letra embebida del archivo.
        SavedLyricsRepository.saveManual(this, currentSong.id, result)
        Log.d(TAG_SYNCSAVE, "saveManualSync(): SavedLyricsRepository.saveManual() completado")

        persistLyricsToAudioFileIfPossible(currentSong, result)

        Toast.makeText(this, "Sincronizacion guardada", Toast.LENGTH_SHORT).show()
        Log.d(TAG_SYNCSAVE, "saveManualSync(): Toast mostrado")
        setResult(RESULT_OK)

        isSyncMode = false
        tvSyncHint.visibility = View.GONE
        llSyncControls.visibility = View.GONE
        btnLyricsSyncToggle.setImageResource(R.drawable.ic_crosshair)
        resetRvLyricsPadding()
        showSynced(lines)
        updateManualSyncButtonsVisibility()
        Log.d(TAG_SYNCSAVE, "saveManualSync(): fin, modo sincronizacion cerrado")
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
        // onCreate (acento spotify_green). El fondo (ambientBackground) se
        // maneja aparte y tiene su propia logica de cache/fallback.
    }

    private fun applyThemeFromBitmap(bitmap: Bitmap) {
        PlayerPaletteTheme.applyAccentFromBitmap(
            bitmap, defaultAccentColor, currentAccentColor
        ) { color ->
            currentAccentColor = color
            applyAccentToControls(color)
        }
    }

    private fun applyThemeFallback() {
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

    companion object {
        // Separacion en dp entre el borde inferior del banner de
        // sincronizacion y la primera linea de la letra.
        private const val SYNC_HINT_GAP_DP = 16f

        // Separacion en dp entre la ultima linea de la letra y el borde
        // superior de la barra inferior de sincronizacion (play/pausa,
        // contador, "Guardar").
        private const val SYNC_CONTROLS_GAP_DP = 16f

        // DEBUG temporal: filtrar con `adb logcat -s MP3_SYNCSAVE`.
        // Se puede quitar (junto con todos los Log.d/w/e marcados como
        // "DEBUG temporal" en este archivo) una vez resuelto el bug de
        // "Guardar" en sincronizacion manual.
        private const val TAG_SYNCSAVE = "MP3_SYNCSAVE"
    }
}