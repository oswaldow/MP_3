package com.learnlayout.mp_3

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * DEBUG: diagnostico de presion de CPU/memoria del proceso, para
 * correlacionar los "tranco" de audio reportados (se traba tantito al
 * abrir otra app, ej. Facebook) con lo que el sistema le esta haciendo
 * a este proceso en ese mismo instante.
 *
 * No cambia NINGUN comportamiento de la app: solo escribe logs.
 *
 * COMO USARLO:
 * 1) Telefono conectado por USB, con "Depuracion por USB" activa.
 * 2) Corre en una terminal:
 *
 *      adb logcat -v time -s MP3_XFADE MP3_PERF MP3_App
 *
 * 3) Pone una cancion a sonar, abre Facebook (o la app que sea) y espera
 *    a que se sienta el "tranco".
 * 4) Copia TODO lo que haya aparecido en esa ventana de tiempo (unos
 *    5-10 segundos antes y despues del tranco, no hace falta mas) y
 *    mandamelo tal cual.
 *
 * QUE BUSCAR EN EL LOG (para referencia, no hace falta que lo interpretes
 * vos, con mandarmelo alcanza):
 * - "MP3_XFADE ... onAudioUnderrun" -> confirma que el corte es un
 *   underrun real del AudioTrack (se quedo sin datos), y dice cuantos ms
 *   llevaba sin recibir buffer nuevo (elapsedSinceLastFeedMs).
 * - "MP3_PERF ... onTrimMemory" -> el sistema le esta pidiendo a este
 *   proceso que libere memoria/CPU justo en ese momento (tipico al abrir
 *   otra app pesada). Si aparece pegado en el tiempo a un
 *   onAudioUnderrun, es la causa mas probable.
 * - "MP3_PERF ... heartbeat" -> snapshot cada 2s mientras suena musica:
 *   importance (100=foreground, 200+=ya paso a segundo plano/cached) y
 *   gcCount (si sube de golpe entre dos heartbeats, hubo GCs de mas justo ahi).
 */
object PerfDiagnostics {

    private const val TAG = "MP3_PERF"
    private const val HEARTBEAT_INTERVAL_MS = 2000L

    private val handler = Handler(Looper.getMainLooper())
    private var heartbeatRunning = false
    private var lastGcCount = Debug.getGlobalGcInvocationCount()
    private var lastImportance = -1

    /** Nombre legible del nivel de onTrimMemory (los valores enteros solos no dicen nada). */
    private fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
            "UI_HIDDEN (la UI ya no es visible, primer aviso al pasar a 2do plano)"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ->
            "RUNNING_MODERATE (foreground, pero el sistema ya anda algo justo)"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
            "RUNNING_LOW (foreground, sistema bastante justo de memoria)"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
            "RUNNING_CRITICAL (foreground, memoria critica, el sistema ya mataria apps de fondo)"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
            "BACKGROUND (proceso en la cola LRU de fondo, nivel bajo)"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE ->
            "MODERATE (proceso en la cola LRU de fondo, nivel medio)"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
            "COMPLETE (proceso en la cola LRU de fondo, primero en morir)"
        else -> "nivel desconocido=$level"
    }

    /** Llamar desde App.onTrimMemory(). Es la senal mas directa de "el sistema esta exigido ahora mismo". */
    fun logTrimMemory(context: Context, level: Int) {
        Log.w(
            TAG,
            "onTrimMemory: ${trimLevelName(level)} uptimeMs=${SystemClock.elapsedRealtime()} " +
                    "importance=${currentImportance(context)}"
        )
    }

    /**
     * Arranca el heartbeat periodico (idempotente, no pasa nada si se llama
     * varias veces). Solo escribe log cuando [isPlaying] devuelve true en
     * ese instante, para no ensuciar el logcat mientras la musica esta en
     * pausa.
     */
    fun startHeartbeat(context: Context, isPlaying: () -> Boolean) {
        if (heartbeatRunning) return
        heartbeatRunning = true
        val appContext = context.applicationContext
        val runnable = object : Runnable {
            override fun run() {
                if (!heartbeatRunning) return
                if (isPlaying()) {
                    logHeartbeat(appContext)
                }
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        handler.post(runnable)
    }

    /** Para el heartbeat. Llamar desde MusicService.onDestroy(). */
    fun stopHeartbeat() {
        heartbeatRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun logHeartbeat(context: Context) {
        val gcCount = Debug.getGlobalGcInvocationCount()
        val gcDelta = gcCount - lastGcCount
        lastGcCount = gcCount

        val importance = currentImportance(context)
        val importanceChanged = importance != lastImportance
        lastImportance = importance

        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)

        Log.d(
            TAG,
            "heartbeat uptimeMs=${SystemClock.elapsedRealtime()} importance=$importance" +
                    (if (importanceChanged) " (CAMBIO)" else "") +
                    " gcCountTotal=$gcCount gcDeltaDesdeUltimoHeartbeat=$gcDelta " +
                    "heapUsadoMb=$usedMb heapMaxMb=$maxMb"
        )
    }

    /**
     * 100=FOREGROUND, 125=FOREGROUND_SERVICE, 200=VISIBLE, 230=PERCEPTIBLE,
     * 325=CANT_SAVE_STATE, 400=SERVICE, 500=CACHED. Cuanto mas alto el
     * numero, mas atras en la cola LRU y mas le recorta el sistema CPU y
     * memoria a este proceso.
     */
    private fun currentImportance(context: Context): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance
        } catch (e: Exception) {
            Log.e(TAG, "currentImportance() fallo: ${e.message}", e)
            -1
        }
    }
}