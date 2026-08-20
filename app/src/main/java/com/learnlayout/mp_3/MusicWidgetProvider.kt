package com.learnlayout.mp_3

import android.appwidget.AppWidgetManager
import android.app.PendingIntent
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
 * Widget de pantalla de inicio con controles de reproduccion.
 *
 * Es RESPONSIVE: segun el tamano que el usuario le de en el launcher,
 * elige entre dos layouts:
 *  - widget_music_player.xml (COMPACT): fila unica con caratula chica,
 *    titulo/artista y prev/play-pause/next. Es el minimo (250x70dp).
 *  - widget_music_player_expanded.xml (EXPANDED): tarjeta vertical con
 *    caratula grande, barra de progreso y controles completos
 *    (shuffle, prev, play-pause, next, repeat). Se activa cuando el
 *    usuario agranda el widget lo suficiente en alto.
 *
 * No habla directo con MusicService en memoria (el widget vive en el
 * proceso del launcher, no en el de la app), asi que:
 *  - Para MOSTRAR el estado usa lo ultimo que MusicService haya guardado
 *    en WidgetStateRepository.
 *  - Para los BOTONES manda las mismas Intents con ACTION_PLAY_PAUSE /
 *    ACTION_NEXT / ACTION_PREVIOUS / ACTION_CYCLE_PLAYBACK_MODE que ya
 *    entiende MusicService.onStartCommand.
 *  - Para la CARATULA usa AlbumArtRepository directamente: como el
 *    widget corre en el mismo proceso que la app, normalmente encuentra
 *    la caratula ya en memoria (MusicService la acaba de cargar) o en
 *    disco, asi que casi siempre se pinta al instante.
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = WidgetStateRepository.getState(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context, appWidgetManager, id, state, art = null))
        }
        loadArtAndRefresh(context, state)
    }

    /**
     * Llamado por el sistema cada vez que el usuario cambia el tamano del
     * widget en el launcher (lo agranda o lo achica arrastrando el borde).
     * Es el gancho que hace posible el layout responsive: sin esto el
     * widget solo re-elegiria layout la proxima vez que la cancion
     * cambiara, no en cuanto el usuario suelta el dedo tras redimensionar.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val state = WidgetStateRepository.getState(context)
        appWidgetManager.updateAppWidget(
            appWidgetId,
            buildRemoteViews(context, appWidgetManager, appWidgetId, state, art = null)
        )
        loadArtAndRefreshSingle(context, appWidgetManager, appWidgetId, state)
    }

    companion object {

        // Alto minimo (en dp) que el usuario debe darle al widget para
        // que pase de COMPACT a EXPANDED. El compacto pide minHeight de
        // 70dp en music_widget_info.xml; con 110dp ya hay espacio comodo
        // para una segunda fila (barra de progreso) mas una tercera
        // (fila de controles con 5 botones).
        private const val EXPANDED_MIN_HEIGHT_DP = 110

        // Radio de las esquinas redondeadas de la caratula del widget
        // compacto, el mismo que usa el mini-player dentro de la app
        // (ver PlayerPanelController.applyRoundedCorners para
        // ivMiniAlbumArt).
        private const val ART_CORNER_RADIUS_DP = 6f

        // Radio de esquina para la caratula grande del layout expandido,
        // igual al que usa bg_album_art.xml para las caratulas grandes
        // del reproductor completo.
        private const val ART_CORNER_RADIUS_EXPANDED_DP = 12f

        /**
         * Llamado por MusicService cada vez que cambia la cancion, el
         * estado de reproduccion, el progreso (throttled) o el modo de
         * reproduccion. Guarda el estado y empuja el refresco a todos
         * los widgets que el usuario tenga agregados.
         */
        fun pushUpdate(
            context: Context,
            song: Song?,
            isPlaying: Boolean,
            positionMs: Long = 0L,
            durationMs: Long = 0L,
            mode: MusicService.PlaybackMode = MusicService.PlaybackMode.NORMAL
        ) {
            val state = WidgetStateRepository.WidgetState(
                title = song?.title ?: "Nada reproduciendose",
                artist = song?.artist ?: "Abre MP_3 para elegir una cancion",
                isPlaying = isPlaying,
                hasSong = song != null,
                songId = song?.id ?: -1L,
                positionMs = positionMs,
                durationMs = durationMs,
                playbackMode = mode
            )
            WidgetStateRepository.saveState(context, state)

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return

            ids.forEach { id ->
                manager.updateAppWidget(id, buildRemoteViews(context, manager, id, state, art = null))
            }
            loadArtAndRefresh(context, state, song)
        }

        /**
         * Decide que layout usar para un widget en particular segun el
         * alto minimo que el usuario le dio en el launcher. Se consulta
         * en cada build en vez de cachearse: es una lectura barata de
         * AppWidgetManager y asi nunca se desincroniza con el tamano real.
         */
        private fun isExpandedLayout(appWidgetManager: AppWidgetManager, appWidgetId: Int): Boolean {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            return minHeight >= EXPANDED_MIN_HEIGHT_DP
        }

        /**
         * Pide la caratula a AlbumArtRepository para TODOS los widgets y,
         * en cuanto llega, vuelve a pintarlos con ella (cada uno con su
         * propio layout segun su tamano). Si ya se tiene el objeto Song
         * completo (caso normal, viniendo de MusicService) se usa
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
            val song = knownSong ?: placeholderSongFrom(state)

            AlbumArtRepository.loadCover(context, song, object : AlbumArtRepository.Callback {
                override fun onCoverReady(bitmap: Bitmap) {
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
                    ids.forEach { id ->
                        val expanded = isExpandedLayout(manager, id)
                        val roundedArt = roundedCorners(context, bitmap, expanded)
                        manager.updateAppWidget(id, buildRemoteViews(context, manager, id, state, roundedArt))
                    }
                }
            })
        }

        /** Igual que [loadArtAndRefresh] pero solo para un widget (usado desde onAppWidgetOptionsChanged). */
        private fun loadArtAndRefreshSingle(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: WidgetStateRepository.WidgetState
        ) {
            if (!state.hasSong) return
            val song = placeholderSongFrom(state)

            AlbumArtRepository.loadCover(context, song, object : AlbumArtRepository.Callback {
                override fun onCoverReady(bitmap: Bitmap) {
                    val expanded = isExpandedLayout(appWidgetManager, appWidgetId)
                    val roundedArt = roundedCorners(context, bitmap, expanded)
                    appWidgetManager.updateAppWidget(
                        appWidgetId,
                        buildRemoteViews(context, appWidgetManager, appWidgetId, state, roundedArt)
                    )
                }
            })
        }

        private fun placeholderSongFrom(state: WidgetStateRepository.WidgetState) = Song(
            id = state.songId,
            title = state.title,
            artist = state.artist,
            duration = 0L,
            uri = Uri.EMPTY
        )

        private fun buildRemoteViews(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: WidgetStateRepository.WidgetState,
            art: Bitmap?
        ): RemoteViews {
            return if (isExpandedLayout(appWidgetManager, appWidgetId)) {
                buildExpandedRemoteViews(context, state, art)
            } else {
                buildCompactRemoteViews(context, state, art)
            }
        }

        private fun buildCompactRemoteViews(
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

            applyArt(views, R.id.widgetAlbumArt, R.id.widgetArtPlaceholder, art)

            views.setOnClickPendingIntent(R.id.widgetBtnPrevious, servicePendingIntent(context, MusicService.ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widgetBtnPlayPause, servicePendingIntent(context, MusicService.ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widgetBtnNext, servicePendingIntent(context, MusicService.ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent(context))

            return views
        }

        private fun buildExpandedRemoteViews(
            context: Context,
            state: WidgetStateRepository.WidgetState,
            art: Bitmap?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player_expanded)

            views.setTextViewText(R.id.widgetSongTitleExp, state.title)
            views.setTextViewText(R.id.widgetSongArtistExp, state.artist)
            views.setImageViewResource(
                R.id.widgetBtnPlayPauseExp,
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )

            applyArt(views, R.id.widgetAlbumArtExp, R.id.widgetArtPlaceholderExp, art)

            // Barra de progreso: si aun no hay duracion conocida (por
            // ejemplo justo al agregar el widget, antes del primer tick
            // de PlaybackEngine) se deja en 0 en vez de dividir por cero.
            val progressPercent = if (state.durationMs > 0) {
                ((state.positionMs.coerceIn(0L, state.durationMs) * 100) / state.durationMs).toInt()
            } else {
                0
            }
            views.setProgressBar(R.id.widgetProgressExp, 100, progressPercent, false)

            // Icono y "encendido" visual de shuffle/repeat: repeat-one usa
            // un icono distinto (con el "1" superpuesto) al de repeat
            // normal, igual que EqualizerActivity/PlayerPanelController
            // distinguen esos dos modos en el resto de la app.
            val (modeIcon, modeActive) = when (state.playbackMode) {
                MusicService.PlaybackMode.SHUFFLE -> R.drawable.ic_shuffle to true
                MusicService.PlaybackMode.REPEAT_ONE -> R.drawable.ic_repeat_one to true
                MusicService.PlaybackMode.NORMAL -> R.drawable.ic_repeat to false
            }
            views.setImageViewResource(R.id.widgetBtnModeExp, modeIcon)
            views.setInt(
                R.id.widgetBtnModeExp,
                "setColorFilter",
                if (modeActive) context.getColor(R.color.spotify_green) else context.getColor(R.color.spotify_gray)
            )

            views.setOnClickPendingIntent(R.id.widgetBtnPreviousExp, servicePendingIntent(context, MusicService.ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widgetBtnPlayPauseExp, servicePendingIntent(context, MusicService.ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widgetBtnNextExp, servicePendingIntent(context, MusicService.ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widgetBtnModeExp, servicePendingIntent(context, MusicService.ACTION_CYCLE_PLAYBACK_MODE))
            views.setOnClickPendingIntent(R.id.widgetRootExp, openAppPendingIntent(context))

            return views
        }

        private fun applyArt(views: RemoteViews, artId: Int, placeholderId: Int, art: Bitmap?) {
            if (art != null) {
                views.setImageViewBitmap(artId, art)
                views.setViewVisibility(artId, android.view.View.VISIBLE)
                views.setViewVisibility(placeholderId, android.view.View.GONE)
            } else {
                views.setViewVisibility(artId, android.view.View.GONE)
                views.setViewVisibility(placeholderId, android.view.View.VISIBLE)
            }
        }

        /**
         * Recorta [source] a un cuadrado con esquinas redondeadas, igual
         * que el resto de las caratulas de la app. RemoteViews no soporta
         * clipToOutline (eso solo aplica a Views reales dentro del propio
         * proceso con jerarquia normal), asi que la unica forma de lograr
         * el mismo look en un widget es recortar el bitmap con Canvas
         * antes de mandarlo. [expanded] elige el radio mas grande que usa
         * la caratula del layout expandido.
         */
        private fun roundedCorners(context: Context, source: Bitmap, expanded: Boolean): Bitmap {
            val size = minOf(source.width, source.height)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
            val radiusDp = if (expanded) ART_CORNER_RADIUS_EXPANDED_DP else ART_CORNER_RADIUS_DP
            val radiusPx = radiusDp * context.resources.displayMetrics.density

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