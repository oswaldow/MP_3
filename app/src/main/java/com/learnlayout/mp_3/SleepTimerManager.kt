package com.learnlayout.mp_3

import android.os.Handler

/**
 * Temporizador para dormir.
 *
 * Puede trabajar de dos formas:
 *
 * 1. Por minutos:
 *    pausa la reproducción después del tiempo indicado.
 *
 * 2. Fin de canción:
 *    pausa cuando termine la canción actual.
 */
class SleepTimerManager(
    private val handler: Handler,
    private val onFire: () -> Unit
) {

    private var timerRunnable: Runnable? = null

    private var endAtMillis: Long = 0L

    private var pauseAtSongEnd: Boolean = false


    /**
     * Programa la pausa automática dentro
     * de la cantidad de minutos indicada.
     */
    fun setMinutes(
        minutes: Int
    ) {

        cancel()

        if (minutes <= 0) {
            return
        }

        val delayMs =
            minutes * 60_000L

        endAtMillis =
            System.currentTimeMillis() +
                    delayMs

        val runnable =
            Runnable {

                timerRunnable = null

                endAtMillis = 0L

                onFire()
            }

        timerRunnable = runnable

        handler.postDelayed(
            runnable,
            delayMs
        )
    }


    /**
     * Pausa cuando termine la canción
     * que está sonando actualmente.
     */
    fun setEndOfSong() {

        cancel()

        pauseAtSongEnd = true
    }


    /**
     * Cancela cualquier temporizador.
     */
    fun cancel() {

        timerRunnable?.let {
            handler.removeCallbacks(it)
        }

        timerRunnable = null

        endAtMillis = 0L

        pauseAtSongEnd = false
    }


    /**
     * Indica si existe cualquier temporizador activo.
     */
    fun isActive(): Boolean =
        timerRunnable != null ||
                pauseAtSongEnd


    /**
     * Indica si está activo el modo
     * "fin de canción".
     */
    fun isEndOfSongActive(): Boolean =
        pauseAtSongEnd


    /**
     * Consume el modo "fin de canción".
     *
     * Devuelve true si estaba activo.
     */
    fun consumeEndOfSongIfActive(): Boolean {

        if (!pauseAtSongEnd) {
            return false
        }

        pauseAtSongEnd = false

        return true
    }


    fun getRemainingMs(): Long {

        if (timerRunnable == null) {
            return -1L
        }

        return (
                endAtMillis -
                        System.currentTimeMillis()
                )
            .coerceAtLeast(0L)
    }
}