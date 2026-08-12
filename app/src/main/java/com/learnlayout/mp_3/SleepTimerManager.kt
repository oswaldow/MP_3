package com.learnlayout.mp_3

import android.os.Handler

/**
 * Temporizador para dormir ("sleep timer"). Dos modos, mutuamente
 * excluyentes:
 *  - Por minutos: se programa un Runnable con handler.postDelayed que
 *    llama a [onFire] cuando se cumple.
 *  - "Fin de canción": no se programa nada, solo se marca una bandera que
 *    MusicService revisa cuando la canción actual termina, y que también
 *    sirve para bloquear el crossfade (para que la canción termine de
 *    forma normal, sin empalmarse con la siguiente).
 *
 * Extraído de MusicService para aislar esta lógica pequeña pero
 * independiente del resto del reproductor.
 */
class SleepTimerManager(
    private val handler: Handler,
    private val onFire: () -> Unit
) {
    private var timerRunnable: Runnable? = null
    private var endAtMillis: Long = 0L
    private var pauseAtSongEnd: Boolean = false

    /** Programa la pausa automática dentro de [minutes] minutos. Cancela cualquier timer previo. */
    fun setMinutes(minutes: Int) {
        cancel()
        if (minutes <= 0) return

        val delayMs = minutes * 60_000L
        endAtMillis = System.currentTimeMillis() + delayMs

        val runnable = Runnable {
            timerRunnable = null
            endAtMillis = 0L
            onFire()
        }
        timerRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /** Pausa cuando termine la canción que está sonando en este momento (sin crossfade hacia la siguiente). */
    fun setEndOfSong() {
        cancel()
        pauseAtSongEnd = true
    }

    fun cancel() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
        endAtMillis = 0L
        pauseAtSongEnd = false
    }

    fun isActive(): Boolean = timerRunnable != null || pauseAtSongEnd

    fun isEndOfSongActive(): Boolean = pauseAtSongEnd

    /** Se llama cuando la canción que estaba sonando terminó. Consume el modo "fin de canción" si estaba activo. */
    fun consumeEndOfSongIfActive(): Boolean {
        if (!pauseAtSongEnd) return false
        pauseAtSongEnd = false
        return true
    }

    /** Milisegundos restantes del timer por minutos, o -1 si no hay uno activo (incluye el modo "fin de canción"). */
    fun getRemainingMs(): Long {
        if (timerRunnable == null) return -1L
        return (endAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    }
}