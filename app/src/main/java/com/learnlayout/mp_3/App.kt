package com.learnlayout.mp_3

import android.app.Application
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        requestNotificationListenerRebindIfGranted()
        pruneOrphanedSongReferences()
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