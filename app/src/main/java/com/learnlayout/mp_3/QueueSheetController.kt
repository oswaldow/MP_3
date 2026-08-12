package com.learnlayout.mp_3

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import androidx.appcompat.app.AlertDialog
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

            val current =
                getMusicService()
                    ?.getCurrentIndex()
                    ?: return@setOnClickListener

            layoutManager
                ?.scrollToPositionWithOffset(
                    current,
                    dp(10)
                )
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

            dialog = null
        }

        queueDialog.show()
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

        val adapter =
            QueueAdapter(
                songs =
                    songs.toMutableList(),

                currentIndex =
                    service.getCurrentIndex(),

                onItemClick = { position ->

                    getMusicService()
                        ?.playAt(position)

                    refreshHeader()
                    refreshList()
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
                }
            )

        recyclerView?.adapter =
            adapter

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

        val imageView =
            currentArt
                ?: return

        if (song == null) {

            imageView.setImageResource(
                R.drawable.ic_music_note
            )

            imageView.setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
            )

            imageView.imageTintList =
                ContextCompat.getColorStateList(
                    activity,
                    R.color.spotify_gray
                )

            imageView.scaleType =
                ImageView.ScaleType.CENTER

            imageView.tag = null

            return
        }

        imageView.tag =
            song.id

        val cached =
            AlbumArtRepository
                .getCachedCover(song)

        if (cached != null) {

            applyCurrentAlbumArt(
                imageView,
                cached
            )

            return
        }

        imageView.setImageResource(
            R.drawable.ic_music_note
        )

        val padding =
            dp(12)

        imageView.setPadding(
            padding,
            padding,
            padding,
            padding
        )

        imageView.imageTintList =
            ContextCompat.getColorStateList(
                activity,
                R.color.spotify_gray
            )

        imageView.scaleType =
            ImageView.ScaleType.CENTER

        AlbumArtRepository.loadCover(
            context = activity,
            song = song,
            callback =
                object :
                    AlbumArtRepository.Callback {

                    override fun onCoverReady(
                        bitmap: Bitmap
                    ) {

                        if (
                            imageView.tag !=
                            song.id
                        ) {
                            return
                        }

                        applyCurrentAlbumArt(
                            imageView,
                            bitmap
                        )
                    }
                },
            isStillNeeded = {
                imageView.tag == song.id
            }
        )
    }

    private fun applyCurrentAlbumArt(
        imageView: ImageView,
        bitmap: Bitmap
    ) {

        imageView.setPadding(
            0,
            0,
            0,
            0
        )

        imageView.imageTintList =
            null

        imageView.scaleType =
            ImageView.ScaleType.CENTER_CROP

        imageView.setImageBitmap(
            bitmap
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

        if (isShowing) {

            refreshHeader()
            refreshList()
        }
    }

    private fun saveQueueAsPlaylist() {

        val service =
            getMusicService()
                ?: return

        val songs =
            service.getSongList()

        if (songs.isEmpty()) {
            return
        }

        val input =
            EditText(activity).apply {

                hint =
                    "Nombre de la playlist"

                inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

                setSingleLine(true)
            }

        val container =
            android.widget.FrameLayout(
                activity
            ).apply {

                setPadding(
                    dp(20),
                    0,
                    dp(20),
                    0
                )

                addView(
                    input,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

        AlertDialog.Builder(
            activity,
            R.style.RoundedAlertDialog
        )
            .setTitle(
                "Guardar cola"
            )
            .setMessage(
                "Guarda las ${songs.size} canciones actuales como una nueva playlist."
            )
            .setView(container)
            .setNegativeButton(
                "Cancelar",
                null
            )
            .setPositiveButton(
                "Guardar"
            ) { _, _ ->

                val name =
                    input.text
                        .toString()
                        .trim()

                if (name.isBlank()) {

                    Toast.makeText(
                        activity,
                        "Escribe un nombre para la playlist",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setPositiveButton
                }

                val playlist =
                    PlaylistRepository.createPlaylist(
                        activity,
                        name
                    )

                songs.forEach { song ->

                    PlaylistRepository.addSongToPlaylist(
                        activity,
                        playlist.id,
                        song.id
                    )
                }

                Toast.makeText(
                    activity,
                    "Playlist \"$name\" guardada",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
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