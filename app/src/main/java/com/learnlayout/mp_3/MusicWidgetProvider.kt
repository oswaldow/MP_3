package com.learnlayout.mp_3

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Widget de pantalla de inicio con controles basicos: titulo/artista de la
 * cancion actual, y botones de anterior / reproducir-pausar / siguiente.
 *
 * No habla directo con MusicService en memoria (el widget vive en el
 * proceso del launcher, no en el de la app), asi que:
 *  - Para MOSTRAR el estado usa lo ultimo que MusicService haya guardado
 *    en WidgetStateRepository.
 *  - Para los BOTONES manda las mismas Intents con ACTION_PLAY_PAUSE /
 *    ACTION_NEXT / ACTION_PREVIOUS que ya entiende MusicService.onStartCommand.
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = WidgetStateRepository.getState(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context, state))
        }
    }

    companion object {

        /**
         * Llamado por MusicService cada vez que cambia la cancion o el
         * estado de reproduccion. Guarda el estado y empuja el refresco a
         * todos los widgets que el usuario tenga agregados.
         */
        fun pushUpdate(context: Context, song: Song?, isPlaying: Boolean) {
            val state = WidgetStateRepository.WidgetState(
                title = song?.title ?: "Nada reproduciendose",
                artist = song?.artist ?: "Abre MP_3 para elegir una cancion",
                isPlaying = isPlaying,
                hasSong = song != null
            )
            WidgetStateRepository.saveState(context, state)

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return

            ids.forEach { id ->
                manager.updateAppWidget(id, buildRemoteViews(context, state))
            }
        }

        private fun buildRemoteViews(
            context: Context,
            state: WidgetStateRepository.WidgetState
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player)

            views.setTextViewText(R.id.widgetSongTitle, state.title)
            views.setTextViewText(R.id.widgetSongArtist, state.artist)
            views.setImageViewResource(
                R.id.widgetBtnPlayPause,
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )

            views.setOnClickPendingIntent(R.id.widgetBtnPrevious, servicePendingIntent(context, MusicService.ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widgetBtnPlayPause, servicePendingIntent(context, MusicService.ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widgetBtnNext, servicePendingIntent(context, MusicService.ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent(context))

            return views
        }

        private fun servicePendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, MusicService::class.java).apply { this.action = action }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getService(context, action.hashCode(), intent, flags)
        }

        private fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, SongListActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(context, 0, intent, flags)
        }
    }
}