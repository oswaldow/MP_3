package com.learnlayout.mp_3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity(), MusicService.PlaybackListener {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var tvSongTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var sbProgress: WaveformSeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnQueue: ImageButton

    private var musicService: MusicService? = null
    private var isBound = false

    private var pendingSongList: ArrayList<Song> = arrayListOf()
    private var pendingStartIndex: Int = 0
    private var hasAppliedPendingList: Boolean = false

    private var isUserSeeking: Boolean = false

    private var activeQueueDialog: BottomSheetDialog? = null
    private var queueTvTitle: TextView? = null
    private var queuePlayPauseBtn: ImageButton? = null
    private var queueProgressBar: ProgressBar? = null
    private var queueRecyclerView: RecyclerView? = null
    private var queueBtnModeNormal: ImageButton? = null
    private var queueBtnModeRepeat: ImageButton? = null
    private var queueBtnModeShuffle: ImageButton? = null
    private var queueTouchHelper: ItemTouchHelper? = null

    private var dragStartY: Float = 0f
    private var isDragging: Boolean = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressPoller = object : Runnable {
        override fun run() {
            val service = musicService
            if (service != null) {
                val current = service.getCurrentPosition()
                val total = service.getDuration()

                if (!isUserSeeking) {
                    sbProgress.max = if (total > 0) total else 0
                    sbProgress.progress = current
                }

                tvCurrentTime.text = formatTime(current.toLong())
                tvTotalTime.text = formatTime(total.toLong())

                btnPlayPause.setImageResource(
                    if (service.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow
                )

                queueProgressBar?.let {
                    it.max = if (total > 0) total else 0
                    it.progress = current
                }
                queuePlayPauseBtn?.setImageResource(
                    if (service.isPlaying()) R.drawable.ic_pause_small else R.drawable.ic_play_small
                )
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            musicService?.setListener(this@MainActivity)
            isBound = true

            if (!hasAppliedPendingList && pendingSongList.isNotEmpty()) {
                hasAppliedPendingList = true
                musicService?.setPlaylist(pendingSongList, pendingStartIndex)
            } else {
                musicService?.getCurrentSong()?.let { song ->
                    onSongChanged(song, musicService?.getCurrentIndex() ?: 0)
                }
            }

            startProgressPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        setupDragToDismiss()
        setupPressAnimations()

        pendingSongList = intent.getParcelableArrayListExtra("song_list") ?: arrayListOf()
        pendingStartIndex = intent.getIntExtra("start_index", 0)

        val serviceIntent = Intent(this, MusicService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.rootMainLayout)
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvArtist = findViewById(R.id.tvArtist)
        sbProgress = findViewById(R.id.sbProgress)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnBack = findViewById(R.id.btnBack)
        btnQueue = findViewById(R.id.btnQueue)
    }

    private fun setupListeners() {
        btnPlayPause.setOnClickListener {
            musicService?.togglePlayPause()
        }

        btnPrevious.setOnClickListener {
            musicService?.playPrevious()
        }

        btnNext.setOnClickListener {
            musicService?.playNext()
        }

        btnBack.setOnClickListener {
            finishWithSlideDown()
        }

        btnQueue.setOnClickListener {
            showQueueSheet()
        }

        sbProgress.listener = object : WaveformSeekBar.OnWaveformSeekListener {
            override fun onProgressChanged(progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch() {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(progress: Int) {
                isUserSeeking = false
                musicService?.seekTo(progress)
            }
        }
    }

    private fun setupDragToDismiss() {
        val dismissThreshold = 160 * resources.displayMetrics.density

        rootLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - dragStartY
                    if (dy > 16) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val clampedDy = max(0f, dy)
                        rootLayout.translationY = clampedDy
                        val progress = (clampedDy / rootLayout.height.toFloat()).coerceIn(0f, 1f)
                        rootLayout.alpha = 1f - (progress * 0.3f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        val dy = max(0f, event.rawY - dragStartY)
                        if (dy > dismissThreshold) {
                            rootLayout.animate()
                                .translationY(rootLayout.height.toFloat())
                                .alpha(0.5f)
                                .setDuration(320)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .withEndAction {
                                    finish()
                                    overridePendingTransition(R.anim.activity_stay, R.anim.activity_stay)
                                }
                                .start()
                        } else {
                            rootLayout.animate()
                                .translationY(0f)
                                .alpha(1f)
                                .setDuration(280)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                        }
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPressAnimations() {
        applyPressAnimation(btnPrevious)
        applyPressAnimation(btnPlayPause)
        applyPressAnimation(btnNext)
    }

    private fun applyPressAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
            }
            false
        }
    }

    private fun finishWithSlideDown() {
        finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.activity_slide_down_out)
    }

    override fun onBackPressed() {
        finishWithSlideDown()
    }

    private fun startProgressPolling() {
        uiHandler.removeCallbacks(progressPoller)
        uiHandler.post(progressPoller)
    }

    private fun stopProgressPolling() {
        uiHandler.removeCallbacks(progressPoller)
    }

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
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.layoutParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
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

    override fun onSongChanged(song: Song, index: Int) {
        runOnUiThread {
            tvSongTitle.text = song.title
            tvArtist.text = song.artist
            sbProgress.setWaveformSeed(song.id)
            queueTvTitle?.text = song.title
            if (activeQueueDialog != null) {
                refreshQueueList()
            }
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
        }
    }

    override fun onProgressChanged(currentMs: Int, totalMs: Int) {
        // La barra se actualiza con progressPoller.
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onStart() {
        super.onStart()
        if (isBound) {
            musicService?.setListener(this)
            musicService?.getCurrentSong()?.let { song ->
                onSongChanged(song, musicService?.getCurrentIndex() ?: 0)
            }
            startProgressPolling()
        }
    }

    override fun onStop() {
        super.onStop()
        stopProgressPolling()
        if (isBound) {
            musicService?.setListener(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressPolling()
        activeQueueDialog?.dismiss()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}