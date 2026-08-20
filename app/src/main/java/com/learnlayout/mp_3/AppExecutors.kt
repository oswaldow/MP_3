package com.learnlayout.mp_3

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object AppExecutors {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Ejecuta [action] en el hilo de fondo compartido. */
    fun runInBackground(action: () -> Unit) {
        backgroundExecutor.execute(action)
    }

    /** Ejecuta [action] en el hilo principal. */
    fun runOnMain(action: () -> Unit) {
        mainHandler.post(action)
    }
}