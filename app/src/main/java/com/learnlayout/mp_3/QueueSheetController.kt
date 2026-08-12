package com.learnlayout.mp_3

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.app.Dialog
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
    private val getAccentColor: () -> Int = {
        ContextCompat.getColor(
            activity,
            R.color.text_primary_light
        )
    },
    private val onModeChanged: () -> Unit = {}
) {

    private var dialog: Dialog? =
        null

    private var tvTitle: TextView? =
        null

    private var tvArtist: TextView? =
        null

    private var tvCount: TextView? =
        null

    private var currentArt: ImageView? =
        null

    private var playPauseBtn: ImageButton? =
        null

    private var progressBar: ProgressBar? =
        null

    private var recyclerView: RecyclerView? =
        null

    private var btnModeNormal: ImageButton? =
        null

    private var btnModeRepeat: ImageButton? =
        null

    private var btnModeShuffle: ImageButton? =
        null

    private var touchHelper: ItemTouchHelper? =
        null

    private var layoutManager: LinearLayoutManager? =
        null

    private var queueAdapter: QueueAdapter? =
        null

    private val artworkController =
        QueueSheetArtworkController(activity)

    private val queueActions =
        QueueSheetActions(activity)

    val isShowing: Boolean
        get() = dialog != null

    fun show() {

        val service =
            getMusicService()
                ?: return

        if (
            service.getSongList().isEmpty()
        ) {
            return
        }

        /*
         * IMPORTANTE:
         *
         * Esta cola ya NO usa BottomSheetDialog.
         *
         * BottomSheetDialog viene con su propia logica de
         * "sheet" (peek height, drag, gesture inset reservado
         * para el gesto de atras) pensada para hojas que NO
         * ocupan toda la pantalla. Pelear contra esa logica
         * para forzarla a fullscreen es fragil y dejaba un
         * hueco en el borde inferior por el que se veia
         * MainActivity.
         *
         * Un Dialog normal, fijado a MATCH_PARENT y sin
         * decorFitsSystemWindows, es mucho mas simple y no
         * reserva ningun espacio: dibuja hasta el borde real
         * de la pantalla, igual que hace el panel de
         * reproduccion.
         */
        val queueDialog =
            Dialog(
                activity,
                R.style.QueueFullscreenDialog
            )

        val view =
            activity.layoutInflater.inflate(
                R.layout.bottom_sheet_queue,
                null,
                false
            )

        queueDialog.setContentView(
            view
        )

        dialog =
            queueDialog

        queueDialog.window?.let { queueWindow ->

            /*
             * Forzamos ancho y alto de la VENTANA (no solo de
             * la vista de contenido) a MATCH_PARENT.
             */
            queueWindow.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            queueWindow.setBackgroundDrawable(
                ColorDrawable(
                    Color.BLACK
                )
            )

            /*
             * EDGE-TO-EDGE:
             *
             * Igual que en SongListActivity (setupEdgeToEdge()),
             * dejamos que la ventana dibuje detras de la barra
             * de estado y la de navegacion.
             */
            WindowCompat.setDecorFitsSystemWindows(
                queueWindow,
                false
            )

            queueWindow.statusBarColor =
                Color.BLACK

            queueWindow.navigationBarColor =
                Color.BLACK
        }

        bindViews(view)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->

            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                )

            v.setPadding(
                0,
                systemBars.top,
                0,
                systemBars.bottom
            )

            insets
        }

        layoutManager =
            LinearLayoutManager(activity)

        recyclerView?.layoutManager =
            layoutManager

        recyclerView?.itemAnimator =
            null

        refreshList()
        refreshHeader()
        refreshModeButtons()

        playPauseBtn?.setOnClickListener {

            getMusicService()
                ?.togglePlayPause()

            refreshHeader()
        }

        view.findViewById<ImageButton>(
            R.id.btnQueueClose
        ).setOnClickListener {

            queueDialog.dismiss()
        }

        view.findViewById<ImageButton>(
            R.id.btnLocateCurrent
        ).setOnClickListener {
            scrollCurrentSongIntoView()
        }

        view.findViewById<TextView>(
            R.id.btnQueueSave
        ).setOnClickListener {

            saveQueueAsPlaylist()
        }

        btnModeNormal?.setOnClickListener {

            getMusicService()
                ?.setPlaybackMode(
                    MusicService.PlaybackMode.NORMAL
                )

            refreshModeButtons()
            refreshList()
            onModeChanged()
        }

        btnModeRepeat?.setOnClickListener {

            getMusicService()
                ?.setPlaybackMode(
                    MusicService.PlaybackMode.REPEAT_ONE
                )

            refreshModeButtons()
            refreshList()
            onModeChanged()
        }

        btnModeShuffle?.setOnClickListener {

            getMusicService()
                ?.setPlaybackMode(
                    MusicService.PlaybackMode.SHUFFLE
                )

            refreshModeButtons()
            refreshList()
            onModeChanged()
        }

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

    private fun bindViews(
        view: View
    ) {

        tvTitle =
            view.findViewById(
                R.id.tvSheetSongTitle
            )

        tvArtist =
            view.findViewById(
                R.id.tvSheetSongArtist
            )

        tvCount =
            view.findViewById(
                R.id.tvQueueCount
            )

        currentArt =
            view.findViewById(
                R.id.ivQueueCurrentArt
            )

        playPauseBtn =
            view.findViewById(
                R.id.btnSheetPlayPause
            )

        progressBar =
            view.findViewById(
                R.id.pbSheetProgress
            )

        recyclerView =
            view.findViewById(
                R.id.rvQueue
            )

        btnModeNormal =
            view.findViewById(
                R.id.btnModeNormal
            )

        btnModeRepeat =
            view.findViewById(
                R.id.btnModeRepeat
            )

        btnModeShuffle =
            view.findViewById(
                R.id.btnModeShuffle
            )
    }

    fun refreshList() {

        val service =
            getMusicService()
                ?: return

        val songs =
            service.getSongList()

        // Guardamos la posicion visual actual antes de reemplazar el adapter.
        // Al crear un QueueAdapter nuevo, RecyclerView tiende a volver a
        // posicionarse en el primer elemento. Eso era lo que provocaba que,
        // al tocar una cancion desde la cola, la lista saltara hasta arriba.
        val lm = layoutManager
        val firstVisiblePosition =
            lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val firstVisibleView =
            if (firstVisiblePosition != RecyclerView.NO_POSITION) {
                lm?.findViewByPosition(firstVisiblePosition)
            } else {
                null
            }
        val firstVisibleOffset =
            if (firstVisibleView != null) {
                lm?.getDecoratedTop(firstVisibleView) ?: 0
            } else {
                0
            }

        val adapter =
            QueueAdapter(
                songs =
                    songs.toMutableList(),

                currentIndex =
                    service.getCurrentIndex(),

                onItemClick = { position ->

                    // No reconstruimos manualmente la lista aqui.
                    // playAt() dispara onSongChanged(), que actualiza la cola.
                    // refreshList() ya conserva la posicion visual actual,
                    // evitando el salto al primer elemento.
                    getMusicService()
                        ?.playAt(position)

                    refreshHeader()
                },

                onMoveFinished = {
                        from,
                        to ->

                    getMusicService()
                        ?.moveQueueItem(
                            from,
                            to
                        )

                    refreshHeader()
                },

                onRemove = { position ->

                    val removed =
                        getMusicService()
                            ?.removeQueueItem(
                                position
                            ) == true

                    if (removed) {

                        Toast.makeText(
                            activity,
                            "Canción quitada de la cola",
                            Toast.LENGTH_SHORT
                        ).show()

                        refreshHeader()
                    }
                },

                onLongPress = { position ->

                    val song =
                        songs.getOrNull(position)

                    if (song != null) {
                        playlistDialogs.showSongItemMenu(song)
                    }
                }
            )

        queueAdapter = adapter

        recyclerView?.adapter =
            adapter

        // Restauramos exactamente el punto donde estaba el usuario.
        // post() garantiza que RecyclerView ya haya asociado el nuevo adapter
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

        val newTouchHelper =
            ItemTouchHelper(
                QueueTouchHelperCallback(
                    adapter = adapter,

                    onSwipeToPlayNext = {
                            position ->

                        val current =
                            service.getCurrentIndex()

                        /*
                         * La canción deslizada se mueve
                         * inmediatamente después de la actual.
                         */
                        val target =
                            if (
                                position > current
                            ) {
                                current + 1
                            } else {
                                current
                            }

                        getMusicService()
                            ?.moveQueueItem(
                                position,
                                target
                            )

                        Toast.makeText(
                            activity,
                            "Sonará a continuación",
                            Toast.LENGTH_SHORT
                        ).show()

                        refreshList()
                    },

                    onSwipeToRemove = {
                            position ->

                        val removed =
                            getMusicService()
                                ?.removeQueueItem(
                                    position
                                ) == true

                        if (removed) {

                            Toast.makeText(
                                activity,
                                "Quitada de la cola",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        refreshList()
                    }
                )
            )

        newTouchHelper.attachToRecyclerView(
            recyclerView
        )

        touchHelper =
            newTouchHelper

        adapter.dragStartListener = {
                viewHolder ->

            newTouchHelper.startDrag(
                viewHolder
            )
        }

        updateQueueCount()
    }

    private fun updateQueueCount() {

        val count =
            getMusicService()
                ?.getSongList()
                ?.size
                ?: 0

        tvCount?.text =
            if (count == 1) {
                "1 canción"
            } else {
                "$count canciones"
            }
    }

    fun refreshHeader() {

        val service =
            getMusicService()
                ?: return

        val currentSong =
            service.getCurrentSong()

        tvTitle?.text =
            currentSong?.title
                ?: "Sin reproducción"

        tvArtist?.text =
            currentSong?.artist
                ?: ""

        refreshCurrentAlbumArt(
            currentSong
        )

        playPauseBtn?.setImageResource(
            if (
                service.isPlaying()
            ) {
                R.drawable.ic_pause_small
            } else {
                R.drawable.ic_play_small
            }
        )

        progressBar?.max =
            service.getDuration()

        progressBar?.progress =
            service.getCurrentPosition()

        updateQueueCount()
    }

    private fun refreshCurrentAlbumArt(
        song: Song?
    ) {
        artworkController.refresh(
            imageView = currentArt,
            song = song
        )
    }

    fun refreshModeButtons() {

        val currentMode =
            getMusicService()
                ?.getPlaybackMode()
                ?: MusicService.PlaybackMode.NORMAL

        val activeTint =
            ColorStateList.valueOf(
                getAccentColor()
            )

        val inactiveTint =
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    activity,
                    R.color.spotify_gray
                )
            )

        fun applyMode(
            button: ImageButton?,
            isActive: Boolean
        ) {

            button?.background =
                null

            button?.imageTintList =
                if (isActive) {
                    activeTint
                } else {
                    inactiveTint
                }
        }

        applyMode(
            btnModeNormal,
            currentMode ==
                    MusicService.PlaybackMode.NORMAL
        )

        applyMode(
            btnModeRepeat,
            currentMode ==
                    MusicService.PlaybackMode.REPEAT_ONE
        )

        applyMode(
            btnModeShuffle,
            currentMode ==
                    MusicService.PlaybackMode.SHUFFLE
        )
    }

    fun updateProgress(
        currentMs: Int,
        totalMs: Int
    ) {

        progressBar?.let {

            it.max =
                if (totalMs > 0) {
                    totalMs
                } else {
                    0
                }

            it.progress =
                currentMs
        }

        playPauseBtn?.setImageResource(
            if (
                getMusicService()
                    ?.isPlaying() == true
            ) {
                R.drawable.ic_pause_small
            } else {
                R.drawable.ic_play_small
            }
        )
    }

    fun onSongChanged(
        song: Song
    ) {

        tvTitle?.text =
            song.title

        tvArtist?.text =
            song.artist

        if (!isShowing) return

        // Al cambiar de canción no reconstruimos el adapter.
        // Reconstruirlo provoca que RecyclerView haga un nuevo layout y
        // produce el pequeño movimiento hacia arriba que se veía al pulsar
        // una canción. Solo actualizamos el indicador de canción actual.
        val newIndex =
            getMusicService()?.getCurrentIndex()
                ?: return

        queueAdapter?.setCurrentIndex(newIndex)
        refreshHeader()
    }


    /**
     * Lleva la canción actual a la vista.
     *
     * Se usa automáticamente solo al abrir la cola. Después, el usuario puede
     * desplazarse libremente y el botón de localizar sirve para volver a la
     * canción actual cuando ya no esté visible.
     */
    private fun scrollCurrentSongIntoView() {
        val currentIndex =
            getMusicService()
                ?.getCurrentIndex()
                ?: return

        val itemCount =
            queueAdapter?.itemCount
                ?: recyclerView?.adapter?.itemCount
                ?: 0

        if (currentIndex !in 0 until itemCount) return

        layoutManager?.scrollToPositionWithOffset(
            currentIndex,
            dp(10)
        )
    }

    private fun saveQueueAsPlaylist() {
        val songs =
            getMusicService()
                ?.getSongList()
                ?: return

        queueActions.saveQueueAsPlaylist(songs)
    }

    private fun dp(
        value: Int
    ): Int {

        return (
                value *
                        activity.resources
                            .displayMetrics
                            .density
                ).toInt()
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}