package com.learnlayout.mp_3

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.app.Dialog
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class QueueSheetController(
    private val activity: AppCompatActivity,
    private val getMusicService: () -> MusicService?,
    // Reutiliza la misma instancia de PlaylistDialogs que ya usa la
    // lista principal de canciones, para que "Agregar a playlist",
    // "Editar nombre y artista" y "Eliminar del dispositivo" se
    // comporten identico (mismos dialogos, mismo flujo de borrado)
    // sin importar si se abren desde la lista o desde la cola.
    private val playlistDialogs: PlaylistDialogs,
    private val getAccentColor: () -> Int = { ContextCompat.getColor(activity, R.color.text_primary_light) },
    private val onModeChanged: () -> Unit = {}
) {

    private var dialog: Dialog? = null
    private var tvTitle: TextView? = null
    private var tvArtist: TextView? = null
    private var tvCount: TextView? = null
    private var currentArt: ImageView? = null
    private var playPauseBtn: ImageButton? = null
    private var progressBar: ProgressBar? = null
    private var recyclerView: RecyclerView? = null
    private var btnModeNormal: ImageButton? = null
    private var btnModeRepeat: ImageButton? = null
    private var btnModeShuffle: ImageButton? = null
    private var touchHelper: ItemTouchHelper? = null
    private var layoutManager: LinearLayoutManager? = null
    private var queueAdapter: QueueAdapter? = null
    private var btnQueueSearch: ImageButton? = null
    private var llQueueSearchBar: View? = null
    private var etQueueSearch: EditText? = null

    // Panel unico "liquid glass" que envuelve TODO el contenido de la
    // cola (header, buscador, lista, botones de modo). Es la propia
    // raiz de bottom_sheet_queue.xml: se fotografia a si mismo, ya que
    // su android:background (pintado por glassBackground) es lo que
    // hay que difuminar para el efecto vidrio del panel completo.
    private var queueGlassPanel: LiquidGlassView? = null

    // Fondo estatico (sin animacion, ver QueueGlassBackgroundController)
    // detras del panel de vidrio, coloreado segun la caratula de la
    // cancion actual. Se recrea en cada show() porque el Dialog y su
    // vista se reinflan cada vez que se abre la cola.
    private var glassBackground: QueueGlassBackgroundController? = null

    // Texto actual de busqueda. Vacio = sin filtro, se muestra la cola
    // completa con drag & drop y swipe habilitados como siempre.
    private var searchQuery: String = ""

    // Mapa "posicion mostrada en pantalla -> indice real en la cola" que
    // solo existe mientras hay una busqueda activa. Se usa para traducir
    // el click/long-press de un resultado filtrado al indice verdadero
    // que conoce MusicService (playAt, etc.), ya que QueueAdapter siempre
    // recibe una lista y no sabe si esta filtrada o no.
    private var filteredIndices: List<Int>? = null

    private val artworkController = QueueSheetArtworkController(activity)
    private val queueActions = QueueSheetActions(activity)

    val isShowing: Boolean get() = dialog != null

    fun show() {
        val service = getMusicService() ?: return
        if (service.getSongList().isEmpty()) return

        /*
         * IMPORTANTE:
         *
         * Esta cola ya NO usa BottomSheetDialog.
         *
         * BottomSheetDialog viene con su propia logica de "sheet" (peek
         * height, drag, gesture inset reservado para el gesto de atras)
         * pensada para hojas que NO ocupan toda la pantalla. Pelear
         * contra esa logica para forzarla a fullscreen es fragil y
         * dejaba un hueco en el borde inferior por el que se veia
         * MainActivity.
         *
         * Un Dialog normal, fijado a MATCH_PARENT y sin
         * decorFitsSystemWindows, es mucho mas simple y no reserva
         * ningun espacio: dibuja hasta el borde real de la pantalla,
         * igual que hace el panel de reproduccion.
         */
        val queueDialog = Dialog(activity, R.style.QueueFullscreenDialog)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_queue, null, false)
        queueDialog.setContentView(view)
        dialog = queueDialog

        queueDialog.window?.let { queueWindow ->
            // Forzamos ancho y alto de la VENTANA (no solo de la vista de
            // contenido) a MATCH_PARENT.
            queueWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Queda como respaldo debajo de todo: la raiz (view) ahora
            // pinta su propio degradado via glassBackground, asi que
            // esto normalmente ni se llega a ver.
            queueWindow.setBackgroundDrawable(ColorDrawable(Color.BLACK))

            // EDGE-TO-EDGE: igual que en SongListActivity (setupEdgeToEdge()),
            // dejamos que la ventana dibuje detras de la barra de estado y
            // la de navegacion.
            WindowCompat.setDecorFitsSystemWindows(queueWindow, false)
            queueWindow.statusBarColor = Color.BLACK
            queueWindow.navigationBarColor = Color.BLACK
        }

        bindViews(view)

        // Fondo estatico "liquid glass": se pinta sobre la vista raiz
        // (view, que ES queueGlassPanel) y el propio panel se
        // fotografia y difumina a si mismo. onBackgroundApplied vuelve
        // a pedirle la foto cada vez que el color queda fijado,
        // incluida la resolucion asincronica de la caratula.
        glassBackground = QueueGlassBackgroundController(
            context = activity,
            targetView = view,
            onBackgroundApplied = { queueGlassPanel?.refreshGlass() }
        )
        glassBackground?.applyForSong(getMusicService()?.getCurrentSong())

        // Radio de esquina en 0: el panel de vidrio es la pantalla
        // completa (edge-to-edge), no una tarjeta flotante, asi que no
        // debe redondear sus esquinas como si fuera el hero del Home.
        queueGlassPanel?.setCornerRadiusDp(0f)
        queueGlassPanel?.attachBackdrop(view)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        layoutManager = LinearLayoutManager(activity)
        recyclerView?.layoutManager = layoutManager
        recyclerView?.itemAnimator = null

        refreshList()
        refreshHeader()
        refreshModeButtons()

        playPauseBtn?.setOnClickListener {
            getMusicService()?.togglePlayPause()
            refreshHeader()
        }

        view.findViewById<ImageButton>(R.id.btnQueueClose).setOnClickListener { queueDialog.dismiss() }
        view.findViewById<ImageButton>(R.id.btnLocateCurrent).setOnClickListener { scrollCurrentSongIntoView() }
        view.findViewById<TextView>(R.id.btnQueueSave).setOnClickListener { saveQueueAsPlaylist() }
        btnQueueSearch?.setOnClickListener { toggleSearchBar() }

        etQueueSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnModeNormal?.setOnClickListener { setQueuePlaybackMode(MusicService.PlaybackMode.NORMAL) }
        btnModeRepeat?.setOnClickListener { setQueuePlaybackMode(MusicService.PlaybackMode.REPEAT_ONE) }
        btnModeShuffle?.setOnClickListener { setQueuePlaybackMode(MusicService.PlaybackMode.SHUFFLE) }

        queueDialog.setOnDismissListener {
            tvTitle = null
            tvArtist = null
            tvCount = null
            currentArt = null
            playPauseBtn = null
            progressBar = null
            recyclerView = null
            btnModeNormal = null
            btnModeRepeat = null
            btnModeShuffle = null
            btnQueueSearch = null
            llQueueSearchBar = null
            etQueueSearch = null
            queueGlassPanel = null
            glassBackground = null
            searchQuery = ""
            filteredIndices = null
            touchHelper = null
            layoutManager = null
            queueAdapter = null
            dialog = null
        }

        queueDialog.show()

        // Cada vez que se abre la cola, la canción que se está reproduciendo
        // debe quedar visible automáticamente. Usamos post() para esperar a
        // que el Dialog y RecyclerView terminen su primer layout; así el
        // desplazamiento se aplica sobre una lista ya medida y no produce
        // saltos visuales.
        recyclerView?.post {
            if (dialog === queueDialog && queueDialog.isShowing) {
                scrollCurrentSongIntoView()
            }
        }
    }

    private fun setQueuePlaybackMode(mode: MusicService.PlaybackMode) {
        getMusicService()?.setPlaybackMode(mode)
        refreshModeButtons()
        refreshList()
        onModeChanged()
    }

    private fun bindViews(view: View) {
        tvTitle = view.findViewById(R.id.tvSheetSongTitle)
        tvArtist = view.findViewById(R.id.tvSheetSongArtist)
        tvCount = view.findViewById(R.id.tvQueueCount)
        currentArt = view.findViewById(R.id.ivQueueCurrentArt)
        playPauseBtn = view.findViewById(R.id.btnSheetPlayPause)
        progressBar = view.findViewById(R.id.pbSheetProgress)
        recyclerView = view.findViewById(R.id.rvQueue)
        btnModeNormal = view.findViewById(R.id.btnModeNormal)
        btnModeRepeat = view.findViewById(R.id.btnModeRepeat)
        btnModeShuffle = view.findViewById(R.id.btnModeShuffle)
        btnQueueSearch = view.findViewById(R.id.btnQueueSearch)
        llQueueSearchBar = view.findViewById(R.id.llQueueSearchBar)
        etQueueSearch = view.findViewById(R.id.etQueueSearch)
        queueGlassPanel = view.findViewById(R.id.queueGlassPanel)
    }

    fun refreshList() {
        val service = getMusicService() ?: return
        val allQueueSongs = service.getSongList()

        // Si hay texto de busqueda activo, se muestra solo lo que coincide
        // por titulo o artista (mismo criterio que la busqueda de la
        // biblioteca principal). filteredIndices guarda, para cada posicion
        // mostrada en pantalla, cual es su indice real dentro de la cola
        // completa -esto es lo que permite traducir un click o un
        // long-press de un resultado filtrado al indice verdadero que
        // conoce MusicService.
        val isSearching = searchQuery.isNotBlank()
        val indices: List<Int> = if (isSearching) {
            allQueueSongs.indices.filter { i ->
                val song = allQueueSongs[i]
                song.title.contains(searchQuery, ignoreCase = true) ||
                        song.artist.contains(searchQuery, ignoreCase = true)
            }
        } else {
            allQueueSongs.indices.toList()
        }

        filteredIndices = if (isSearching) indices else null
        val songs = indices.map { allQueueSongs[it] }
        val displayCurrentIndex = if (isSearching) indices.indexOf(service.getCurrentIndex()) else service.getCurrentIndex()

        // Guardamos la posicion visual actual antes de reemplazar el adapter.
        // Al crear un QueueAdapter nuevo, RecyclerView tiende a volver a
        // posicionarse en el primer elemento. Eso era lo que provocaba que,
        // al tocar una cancion desde la cola, la lista saltara hasta arriba.
        val lm = layoutManager
        val firstVisiblePosition = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val firstVisibleView = if (firstVisiblePosition != RecyclerView.NO_POSITION) lm?.findViewByPosition(firstVisiblePosition) else null
        val firstVisibleOffset = if (firstVisibleView != null) lm?.getDecoratedTop(firstVisibleView) ?: 0 else 0

        val adapter = QueueAdapter(
            songs = songs.toMutableList(),
            currentIndex = displayCurrentIndex,
            searchMode = isSearching,
            onItemClick = { position ->
                // En modo busqueda, "position" es la posicion dentro de la
                // lista filtrada: hay que traducirla al indice real de la
                // cola antes de pedir la reproduccion.
                val realIndex = if (isSearching) indices.getOrNull(position) else position
                if (realIndex != null) {
                    // No reconstruimos manualmente la lista aqui. playAt()
                    // dispara onSongChanged(), que actualiza la cola.
                    // refreshList() ya conserva la posicion visual actual,
                    // evitando el salto al primer elemento.
                    getMusicService()?.playAt(realIndex)
                }
                // Elegir un resultado cierra la busqueda: ya cumplio su
                // proposito (encontrar y saltar a esa cancion) y el usuario
                // vuelve a ver la cola completa de inmediato.
                if (isSearching) toggleSearchBar()
                refreshHeader()
            },
            onMoveFinished = { from, to ->
                // El arrastre esta deshabilitado durante la busqueda (ver
                // searchMode en QueueAdapter / touchHelper mas abajo), asi
                // que "from"/"to" siempre son indices reales de la cola
                // cuando esto se llega a invocar.
                getMusicService()?.moveQueueItem(from, to)
                refreshHeader()
            },
            onRemove = { position ->
                val realIndex = if (isSearching) indices.getOrNull(position) else position
                if (realIndex != null) {
                    val removed = getMusicService()?.removeQueueItem(realIndex) == true
                    if (removed) {
                        Toast.makeText(activity, "Canción quitada de la cola", Toast.LENGTH_SHORT).show()
                        refreshList()
                        refreshHeader()
                    }
                }
            },
            onLongPress = { position ->
                songs.getOrNull(position)?.let { playlistDialogs.showSongItemMenu(it) }
            }
        )

        queueAdapter = adapter
        recyclerView?.adapter = adapter

        // Restauramos exactamente el punto donde estaba el usuario. post()
        // garantiza que RecyclerView ya haya asociado el nuevo adapter
        // antes de aplicar la posicion.
        if (firstVisiblePosition != RecyclerView.NO_POSITION) {
            recyclerView?.post {
                if (recyclerView?.adapter === adapter) {
                    layoutManager?.scrollToPositionWithOffset(
                        firstVisiblePosition.coerceIn(0, (songs.size - 1).coerceAtLeast(0)),
                        firstVisibleOffset
                    )
                }
            }
        }

        // *** FIX del bug "buscar en la cola + deslizar no pone la
        // cancion a continuacion" ***
        // refreshList() se llama en CADA tecla que se escribe en el
        // buscador de la cola (ver TextWatcher en show()). Antes de este
        // fix, cada llamada creaba un ItemTouchHelper nuevo y lo pegaba
        // al RecyclerView con attachToRecyclerView() SIN desconectar el
        // anterior. RecyclerView permite varios OnItemTouchListener a la
        // vez y los revisa en el orden en que se agregaron, asi que el
        // PRIMER ItemTouchHelper (el mas viejo) seguia siendo el que
        // interceptaba el gesto de swipe -nunca el que se acababa de
        // construir con el texto de busqueda actual-.
        // Ese helper viejo tiene, en sus lambdas (onSwipeToPlayNext /
        // onSwipeToRemove), los valores de "indices" e "isSearching"
        // capturados de un estado de busqueda desactualizado. Al
        // traducir la posicion mostrada al indice real de la cola con
        // esos datos viejos, el resultado no correspondia a la cancion
        // que el usuario realmente estaba viendo y deslizando.
        // Desconectar el touchHelper anterior antes de pegar el nuevo
        // asegura que solo hay UN ItemTouchHelper vivo a la vez, siempre
        // el mas reciente, con los indices correctos.
        touchHelper?.attachToRecyclerView(null)

        val newTouchHelper = ItemTouchHelper(
            QueueTouchHelperCallback(
                adapter = adapter,
                dragEnabled = !isSearching,
                onSwipeToPlayNext = { position ->
                    val realIndex = if (isSearching) indices.getOrNull(position) else position
                    if (realIndex != null) {
                        val current = service.getCurrentIndex()
                        // La canción deslizada se mueve inmediatamente
                        // después de la actual.
                        val target = if (realIndex > current) current + 1 else current
                        getMusicService()?.moveQueueItem(realIndex, target)
                        Toast.makeText(activity, "Sonará a continuación", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                },
                onSwipeToRemove = { position ->
                    val realIndex = if (isSearching) indices.getOrNull(position) else position
                    if (realIndex != null) {
                        val removed = getMusicService()?.removeQueueItem(realIndex) == true
                        if (removed) {
                            Toast.makeText(activity, "Quitada de la cola", Toast.LENGTH_SHORT).show()
                        }
                        refreshList()
                    }
                }
            )
        )
        newTouchHelper.attachToRecyclerView(recyclerView)
        touchHelper = newTouchHelper
        if (!isSearching) {
            adapter.dragStartListener = { viewHolder -> newTouchHelper.startDrag(viewHolder) }
        }

        updateQueueCount()
    }

    private fun updateQueueCount() {
        val count = getMusicService()?.getSongList()?.size ?: 0
        tvCount?.text = if (count == 1) "1 canción" else "$count canciones"
    }

    fun refreshHeader() {
        val service = getMusicService() ?: return
        val currentSong = service.getCurrentSong()

        tvTitle?.text = currentSong?.title ?: "Sin reproducción"
        tvArtist?.text = currentSong?.artist ?: ""
        refreshCurrentAlbumArt(currentSong)

        playPauseBtn?.setImageResource(if (service.isPlaying()) R.drawable.ic_pause_small else R.drawable.ic_play_small)
        progressBar?.max = service.getDuration()
        progressBar?.progress = service.getCurrentPosition()

        updateQueueCount()
    }

    private fun refreshCurrentAlbumArt(song: Song?) {
        artworkController.refresh(imageView = currentArt, song = song)
    }

    fun refreshModeButtons() {
        val currentMode = getMusicService()?.getPlaybackMode() ?: MusicService.PlaybackMode.NORMAL
        val activeTint = ColorStateList.valueOf(getAccentColor())
        val inactiveTint = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.spotify_gray))

        fun applyMode(button: ImageButton?, isActive: Boolean) {
            button?.background = null
            button?.imageTintList = if (isActive) activeTint else inactiveTint
        }

        applyMode(btnModeNormal, currentMode == MusicService.PlaybackMode.NORMAL)
        applyMode(btnModeRepeat, currentMode == MusicService.PlaybackMode.REPEAT_ONE)
        applyMode(btnModeShuffle, currentMode == MusicService.PlaybackMode.SHUFFLE)
    }

    fun updateProgress(currentMs: Int, totalMs: Int) {
        progressBar?.let {
            it.max = if (totalMs > 0) totalMs else 0
            it.progress = currentMs
        }
        playPauseBtn?.setImageResource(
            if (getMusicService()?.isPlaying() == true) R.drawable.ic_pause_small else R.drawable.ic_play_small
        )
    }

    fun onSongChanged(song: Song) {
        tvTitle?.text = song.title
        tvArtist?.text = song.artist

        if (!isShowing) return

        // Al cambiar de canción no reconstruimos el adapter. Reconstruirlo
        // provoca que RecyclerView haga un nuevo layout y produce el
        // pequeño movimiento hacia arriba que se veía al pulsar una
        // canción. Solo actualizamos el indicador de canción actual.
        val newIndex = getMusicService()?.getCurrentIndex() ?: return
        queueAdapter?.setCurrentIndex(newIndex)
        refreshHeader()

        // Mantiene el degradado del vidrio en sintonia con la caratula
        // de la nueva cancion mientras la cola sigue abierta.
        glassBackground?.applyForSong(song)
    }

    /**
     * Lleva la canción actual a la vista.
     *
     * Se usa automáticamente solo al abrir la cola. Después, el usuario puede
     * desplazarse libremente y el botón de localizar sirve para volver a la
     * canción actual cuando ya no esté visible.
     */
    private fun scrollCurrentSongIntoView() {
        val currentIndex = getMusicService()?.getCurrentIndex() ?: return
        val itemCount = queueAdapter?.itemCount ?: recyclerView?.adapter?.itemCount ?: 0
        if (currentIndex !in 0 until itemCount) return
        layoutManager?.scrollToPositionWithOffset(currentIndex, dp(10))
    }

    /**
     * Muestra u oculta la barra de busqueda de la cola. Al ocultarla se
     * limpia el texto y el filtro, volviendo a mostrar la cola completa
     * con drag & drop habilitado de nuevo.
     */
    private fun toggleSearchBar() {
        val bar = llQueueSearchBar ?: return
        val isVisible = bar.visibility == View.VISIBLE
        val imm = activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager

        if (isVisible) {
            bar.visibility = View.GONE
            etQueueSearch?.setText("")
            searchQuery = ""
            imm.hideSoftInputFromWindow(etQueueSearch?.windowToken, 0)
            refreshList()
        } else {
            bar.visibility = View.VISIBLE
            etQueueSearch?.requestFocus()
            imm.showSoftInput(etQueueSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun saveQueueAsPlaylist() {
        val songs = getMusicService()?.getSongList() ?: return
        queueActions.saveQueueAsPlaylist(songs)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    fun dismiss() {
        dialog?.dismiss()
    }
}