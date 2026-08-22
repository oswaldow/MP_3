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
}