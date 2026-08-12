package com.learnlayout.mp_3

import android.os.Handler
import android.os.Looper

class PlaybackProgressController(
    private val getMusicService: () -> MusicService?,
    private val isPlayerVisible: () -> Boolean,
    private val onProgress: (currentMs: Int, totalMs: Int) -> Unit,
    private val intervalMs: Long = 500L
) {

    private val handler = Handler(Looper.getMainLooper())

    private val progressPoller = object : Runnable {
        override fun run() {
            val service = getMusicService()

            if (service != null && isPlayerVisible()) {
                onProgress(
                    service.getCurrentPosition(),
                    service.getDuration()
                )
            }

            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        handler.removeCallbacks(progressPoller)
        handler.post(progressPoller)
    }

    fun stop() {
        handler.removeCallbacks(progressPoller)
    }
}