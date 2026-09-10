package com.learnlayout.mp_3

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.StrictMode
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

class App : Application() {
    override fun onCreate() {
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
        super.onCreate()
        CrashHandler.install(this)
        warmUpSharedPreferences()
        requestNotificationListenerRebindIfGranted()
        pruneOrphanedSongReferences()
    }

    // DEBUG: detecta trabajo costoso (disco, red, DB) hecho por error en el
    // hilo principal, y leaks de recursos (cursors/streams no cerrados) o de
    // Activity. Solo corre en builds de debug (BuildConfig.DEBUG), nunca en
    // release. Los avisos salen en Logcat bajo el tag "StrictMode".
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }

    /**
     * Android carga cada archivo de SharedPreferences a memoria de forma
     * perezosa (async) la PRIMERA vez que se pide con getSharedPreferences().
     * Si esa primera pedida cae justo en el arranque (por ejemplo
     * SettingsRepository.isOnboardingCompleted() en SongListActivity.onCreate),
     * el hilo principal se queda esperando esa carga y se traba (confirmado:
     * ~511ms perdidos ahi antes de este fix; el profiling mas reciente
     * confirma que ese evento ya no aparece).
     *
     * Ac谩 se piden todos los archivos de prefs del proyecto en el hilo de
     * background compartido (AppExecutors) apenas arranca el proceso.
     * Android cachea la instancia por nombre, asi que cuando cada
     * Activity/Repository los pida de verdad mas adelante, ya van a estar
     * cargados en memoria y la lectura es instantanea. No cambia ningun
     * dato ni comportamiento, solo adelanta el costo.
     */
    private fun warmUpSharedPreferences() {
        AppExecutors.runInBackground {
            runCatching {
                val names = listOf(
                    "mp3_settings",              // SettingsRepository (onboarding, WhatsApp reader, etc.)
                    "mp3_queue_state",           // QueueStateRepository (restaurar cola/cancion al abrir)
                    "equalizer_prefs",           // EqualizerRepository
                    "bass_virtualizer_prefs",    // BassVirtualizerRepository
                    "mp3_playback_state",        // PlaybackStateRepository
                    "saved_lyrics_prefs",        // SavedLyricsRepository
                    "song_metadata_prefs",       // SongMetadataRepository
                    "widget_state_prefs",        // WidgetStateRepository
                    "monito_prefs",              // MonitoPrefs
                    "album_art_manual_overrides" // AlbumArtRepository
                )
                names.forEach { name ->
                    getSharedPreferences(name, Context.MODE_PRIVATE)
                }
            }.onFailure {
                Log.w("MP3_App", "warmUpSharedPreferences() fallo: ${it.message}", it)
            }
        }
    }

    // DEBUG: el sistema llama esto cuando le esta exigiendo memoria/CPU al
    // proceso (por ejemplo, justo al abrir una app pesada como Facebook).
    // Es la senal mas directa que existe para correlacionar los "tranco"
    // de audio reportados con presion real del sistema en ese instante.
    // No cambia nada del comportamiento de la app, solo deja rastro en
    // logcat bajo el tag MP3_PERF. Ver PerfDiagnostics.kt para el detalle
    // de como leer estos logs.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        PerfDiagnostics.logTrimMemory(this, level)
    }

    private fun requestNotificationListenerRebindIfGranted() {
        val alreadyGranted = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)
        if (!alreadyGranted) return

        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, WhatsAppNotificationReaderService::class.java)
            )
        }.onFailure {
            Log.w("MP3_App", "requestRebind() fallo en el arranque: ${it.message}", it)
        }
    }

    /**
     * Limpia, en segundo plano, las referencias a songId "huerfanos"
     * que hayan quedado sueltas en playlists (incluida Favoritos),
     * contadores de reproduccion y ganancia (ver SongIdMigrator). Estas
     * referencias se acumulan cuando MediaStore le asigna un _ID nuevo
     * a un archivo por fuera del mecanismo de remapSongId() (por
     * ejemplo, reescaneos del sistema en MIUI/HyperOS). Se corre una
     * vez por arranque de proceso; es una limpieza barata (solo compara
     * contra el _ID actual de MediaStore) y no bloquea la UI.
     */
    private fun pruneOrphanedSongReferences() {
        AppExecutors.runInBackground {
            runCatching {
                val removed = SongIdMigrator.pruneOrphanedReferences(this)
                Log.i("MP3_App", "pruneOrphanedSongReferences(): $removed referencias huerfanas eliminadas en el arranque")
            }.onFailure {
                Log.w("MP3_App", "pruneOrphanedSongReferences() fallo en el arranque: ${it.message}", it)
            }
        }
    }
}