package com.learnlayout.mp_3

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.widget.RemoteViews

/**
 * Widget de pantalla de inicio con controles basicos: caratula, titulo y
 * artista de la cancion actual, y botones de anterior / reproducir-pausar
 * / siguiente.
 *
 * No habla directo con MusicService en memoria (el widget vive en el
 * proceso del launcher, no en el de la app), asi que:
 *  - Para MOSTRAR el estado usa lo ultimo que MusicService haya guardado
 *    en WidgetStateRepository.
 *  - Para los BOTONES manda las mismas Intents con ACTION_PLAY_PAUSE /
 *    ACTION_NEXT / ACTION_PREVIOUS que ya entiende MusicService.onStartCommand.
 *  - Para la CARATULA usa AlbumArtRepository directamente: como el widget
 *    corre en el mismo proceso que la app, normalmente encuentra la
 *    caratula ya en memoria (MusicService la acaba de cargar) o en disco,
 *    asi que casi siempre se pinta al instante.
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = WidgetStateRepository.getState(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context, state, art = null))
        }
        loadArtAndRefresh(context, state)
    }

    companion object {

        // Radio de las esquinas redondeadas de la caratula del widget,
        // el mismo que usa el mini-player dentro de la app (ver
        // PlayerPanelController.applyRoundedCorners para ivMiniAlbumArt).
        private const val ART_CORNER_RADIUS_DP = 6f

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
                hasSong = song != null,
                songId = song?.id ?: -1L
            )
            WidgetStateRepository.saveState(context, state)

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return

            ids.forEach { id ->
                manager.updateAppWidget(id, buildRemoteViews(context, state, art = null))
            }
            loadArtAndRefresh(context, state, song)
        }

        /**
         * Pide la caratula a AlbumArtRepository y, en cuanto llega, vuelve
         * a pintar todos los widgets con ella. Si ya se tiene el objeto
         * Song completo (caso normal, viniendo de MusicService) se usa
         * directo; si no (por ejemplo el sistema recreando el widget tras
         * un reinicio del telefono) se arma uno minimo con lo guardado en
         * WidgetStateRepository, suficiente para que AlbumArtRepository
         * encuentre la cache en disco por artista+titulo.
         */
        private fun loadArtAndRefresh(
            context: Context,
            state: WidgetStateRepository.WidgetState,
            knownSong: Song? = null
        ) {
            if (!state.hasSong) return
            val song = knownSong ?: Song(
                id = state.songId,
                title = state.title,
                artist = state.artist,
                duration = 0L,
                uri = Uri.EMPTY
            )

            AlbumArtRepository.loadCover(context, song, object : AlbumArtRepository.Callback {
                override fun onCoverReady(bitmap: Bitmap) {
                    val roundedArt = roundedCorners(context, bitmap)
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
                    ids.forEach { id ->
                        manager.updateAppWidget(id, buildRemoteViews(context, state, roundedArt))
                    }
                }
            })
        }

        private fun buildRemoteViews(
            context: Context,
            state: WidgetStateRepository.WidgetState,
            art: Bitmap?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player)

            views.setTextViewText(R.id.widgetSongTitle, state.title)
            views.setTextViewText(R.id.widgetSongArtist, state.artist)
            views.setImageViewResource(
                R.id.widgetBtnPlayPause,
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )

            if (art != null) {
                views.setImageViewBitmap(R.id.widgetAlbumArt, art)
                views.setViewVisibility(R.id.widgetAlbumArt, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.widgetArtPlaceholder, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.widgetAlbumArt, android.view.View.GONE)
                views.setViewVisibility(R.id.widgetArtPlaceholder, android.view.View.VISIBLE)
            }

            views.setOnClickPendingIntent(R.id.widgetBtnPrevious, servicePendingIntent(context, MusicService.ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widgetBtnPlayPause, servicePendingIntent(context, MusicService.ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widgetBtnNext, servicePendingIntent(context, MusicService.ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent(context))

            return views
        }

        /**
         * Recorta [source] a un cuadrado con esquinas redondeadas, igual
         * que el resto de las caratulas de la app. RemoteViews no soporta
         * clipToOutline (eso solo aplica a Views reales dentro del propio
         * proceso con jerarquia normal), asi que la unica forma de lograr
         * el mismo look en un widget es recortar el bitmap con Canvas
         * antes de mandarlo.
         */
        private fun roundedCorners(context: Context, source: Bitmap): Bitmap {
            val size = minOf(source.width, source.height)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
            val radiusPx = ART_CORNER_RADIUS_DP * context.resources.displayMetrics.density

            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

            val left = (source.width - size) / 2
            val top = (source.height - size) / 2
            canvas.drawBitmap(source, -left.toFloat(), -top.toFloat(), paint)

            return output
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