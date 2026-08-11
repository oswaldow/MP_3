package com.learnlayout.mp_3

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Encapsula el BottomSheetDialog de la cola de reproduccion: inflar,
 * mostrar, refrescar lista/encabezado/botones de modo, y recibir
 * actualizaciones de progreso y de cambio de cancion desde la Activity.
 *
 * No guarda referencia directa a MusicService: la recibe via [getMusicService]
 * cada vez que la necesita, igual que hacia SongListActivity antes.
 */
class QueueSheetController(
    private val activity: AppCompatActivity,
    private val getMusicService: () -> MusicService?,
    private val getAccentColor: () -> Int = {
        ContextCompat.getColor(activity, R.color.text_primary_light)
    },
    // Avisa cada vez que el modo de reproduccion cambia desde esta hoja,
    // para que quien arme este controller pueda sincronizar el icono de
    // modo que se muestra en el reproductor normal (btnMiniPlayMode).
    private val onModeChanged: () -> Unit = {}
) {

    private var dialog: BottomSheetDialog? = null
    private var tvTitle: TextView? = null
    private var playPauseBtn: ImageButton? = null
    private var progressBar: ProgressBar? = null
    private var recyclerView: RecyclerView? = null
    private var btnModeNormal: ImageButton? = null
    private var btnModeRepeat: ImageButton? = null
    private var btnModeShuffle: ImageButton? = null
    private var touchHelper: ItemTouchHelper? = null

    val isShowing: Boolean
        get() = dialog != null

    fun show() {
        val service = getMusicService() ?: return
        if (service.getSongList().isEmpty()) return

        val sheetDialog = BottomSheetDialog(activity, R.style.RoundedBottomSheetDialog)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_queue, null)
        sheetDialog.setContentView(view)
        dialog = sheetDialog

        sheetDialog.setOnShowListener { dialogInterface ->
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

        tvTitle = view.findViewById(R.id.tvSheetSongTitle)
        playPauseBtn = view.findViewById(R.id.btnSheetPlayPause)
        progressBar = view.findViewById(R.id.pbSheetProgress)
        recyclerView = view.findViewById(R.id.rvQueue)
        btnModeNormal = view.findViewById(R.id.btnModeNormal)
        btnModeRepeat = view.findViewById(R.id.btnModeRepeat)
        btnModeShuffle = view.findViewById(R.id.btnModeShuffle)

        val layoutManager = LinearLayoutManager(activity)
        recyclerView?.layoutManager = layoutManager

        refreshList()
        refreshHeader()
        refreshModeButtons()

        playPauseBtn?.setOnClickListener {
            getMusicService()?.togglePlayPause()
        }

        val btnLocateCurrent: ImageButton = view.findViewById(R.id.btnLocateCurrent)
        btnLocateCurrent.setOnClickListener {
            layoutManager.scrollToPositionWithOffset(getMusicService()?.getCurrentIndex() ?: 0, 0)
        }

        btnModeNormal?.setOnClickListener {
            getMusicService()?.setPlaybackMode(MusicService.PlaybackMode.NORMAL)
            refreshModeButtons()
            refreshList()
            onModeChanged()
        }

        btnModeRepeat?.setOnClickListener {
            getMusicService()?.setPlaybackMode(MusicService.PlaybackMode.REPEAT_ONE)
            refreshModeButtons()
            refreshList()
            onModeChanged()
        }

        btnModeShuffle?.setOnClickListener {
            getMusicService()?.setPlaybackMode(MusicService.PlaybackMode.SHUFFLE)
            refreshModeButtons()
            refreshList()
            onModeChanged()
        }

        sheetDialog.setOnDismissListener {
            tvTitle = null
            playPauseBtn = null
            progressBar = null
            recyclerView = null
            btnModeNormal = null
            btnModeRepeat = null
            btnModeShuffle = null
            touchHelper = null
            dialog = null
        }

        sheetDialog.show()
    }

    fun refreshList() {
        val service = getMusicService() ?: return
        val adapter = QueueAdapter(
            service.getSongList().toMutableList(),
            service.getCurrentIndex(),
            onItemClick = { position ->
                getMusicService()?.playAt(position)
                dialog?.dismiss()
            },
            onMoveFinished = { from, to ->
                getMusicService()?.moveQueueItem(from, to)
            }
        )
        recyclerView?.adapter = adapter

        val newTouchHelper = ItemTouchHelper(
            QueueTouchHelperCallback(adapter) { position ->
                val current = service.getCurrentIndex()
                val target = if (position > current) current + 1 else current
                getMusicService()?.moveQueueItem(position, target)
                Toast.makeText(activity, "Sonará a continuación", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        )
        newTouchHelper.attachToRecyclerView(recyclerView)
        touchHelper = newTouchHelper
        adapter.dragStartListener = { viewHolder -> newTouchHelper.startDrag(viewHolder) }
    }

    fun refreshHeader() {
        val service = getMusicService() ?: return
        val currentSong = service.getCurrentSong()
        tvTitle?.text = currentSong?.title ?: ""
        playPauseBtn?.setImageResource(
            if (service.isPlaying()) R.drawable.ic_pause_small else R.drawable.ic_play_small
        )
        progressBar?.max = service.getDuration()
        progressBar?.progress = service.getCurrentPosition()
    }

    fun refreshModeButtons() {
        val currentMode = getMusicService()?.getPlaybackMode() ?: MusicService.PlaybackMode.NORMAL
        val activeTint = ColorStateList.valueOf(getAccentColor())
        val inactiveTint = ColorStateList.valueOf(
            ContextCompat.getColor(activity, R.color.spotify_gray)
        )

        fun applyMode(button: ImageButton?, isActive: Boolean) {
            button?.background = null
            button?.imageTintList = if (isActive) activeTint else inactiveTint
        }

        applyMode(btnModeNormal, currentMode == MusicService.PlaybackMode.NORMAL)
        applyMode(btnModeRepeat, currentMode == MusicService.PlaybackMode.REPEAT_ONE)
        applyMode(btnModeShuffle, currentMode == MusicService.PlaybackMode.SHUFFLE)
    }

    // Llamado desde el poller de progreso de la Activity (cada 500ms).
    fun updateProgress(currentMs: Int, totalMs: Int) {
        progressBar?.let {
            it.max = if (totalMs > 0) totalMs else 0
            it.progress = currentMs
        }
        playPauseBtn?.setImageResource(
            if (getMusicService()?.isPlaying() == true) R.drawable.ic_pause_small else R.drawable.ic_play_small
        )
    }

    // Llamado desde onSongChanged() de la Activity.
    fun onSongChanged(song: Song) {
        tvTitle?.text = song.title
        if (isShowing) {
            refreshList()
        }
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}