package com.learnlayout.mp_3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.C

/**
 * Todo lo relacionado a hacerle saber al sistema qué está sonando:
 * MediaSession (pantalla de bloqueo, controles multimedia), notificación
 * de reproducción y su canal. Extraído de MusicService para separar "cómo
 * se reproduce" de "cómo se le avisa al sistema lo que está sonando".
 *
 * No sabe nada de ExoPlayer ni de la cola de canciones: MusicService le
 * pasa la canción y el estado, y este helper arma MediaSession/Notification.
 */
class PlaybackNotifier(
    private val context: Context,
    private val channelId: String,
    actionCallback: ActionCallback
) {
    interface ActionCallback {
        fun onPlayRequested()
        fun onPauseRequested()
        fun onNextRequested()
        fun onPreviousRequested()
        fun onSeekRequested(positionMs: Long)
        fun onStopRequested()
    }

    private var currentAlbumArt: Bitmap? = null

    val mediaSession: MediaSessionCompat = MediaSessionCompat(context, "MusicServiceSession").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = actionCallback.onPlayRequested()
            override fun onPause() = actionCallback.onPauseRequested()
            override fun onSkipToNext() = actionCallback.onNextRequested()
            override fun onSkipToPrevious() = actionCallback.onPreviousRequested()
            override fun onStop() = actionCallback.onStopRequested()
            override fun onSeekTo(pos: Long) = actionCallback.onSeekRequested(pos)
        })
        // Hace que el control multimedia del sistema (pantalla de bloqueo,
        // banner de reproduccion, quick settings) abra la app al tocarlo.
        setSessionActivity(openAppPendingIntent())
        isActive = true
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Reproduccion de musica",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Controles de reproduccion de MP3"
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun buildIdleNotification(): Notification {
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("MP_3")
            .setContentText("Listo para reproducir")
            .setColor(ContextCompat.getColor(context, R.color.purple_primary))
            .setColorized(true)
            .setOngoing(false)
            .setContentIntent(openAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun buildNotification(song: Song, playing: Boolean): Notification {
        val playPauseIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow

        val previousPendingIntent = servicePendingIntent(MusicService.ACTION_PREVIOUS)
        val playPausePendingIntent = servicePendingIntent(MusicService.ACTION_PLAY_PAUSE)
        val nextPendingIntent = servicePendingIntent(MusicService.ACTION_NEXT)
        val deletePendingIntent = servicePendingIntent(MusicService.ACTION_STOP)

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon(currentAlbumArt)
            // Notificacion "colorizada": el sistema tine el fondo con este
            // color (mismo morado de marca de la app) en vez del gris
            // generico, tanto en la barra de notificaciones como en el
            // banner de la pantalla de bloqueo.
            .setColor(ContextCompat.getColor(context, R.color.purple_primary))
            .setColorized(true)
            .setOngoing(false)
            .setContentIntent(openAppPendingIntent())
            .setDeleteIntent(deletePendingIntent)
            .addAction(R.drawable.ic_skip_previous, "Anterior", previousPendingIntent)
            .addAction(playPauseIcon, "Reproducir/Pausar", playPausePendingIntent)
            .addAction(R.drawable.ic_skip_next, "Siguiente", nextPendingIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Publica en la MediaSession el titulo, artista y duracion de la
     * cancion actual, y dispara la carga asincrona de la caratula. Es
     * indispensable para que la pantalla de bloqueo y la notificacion
     * multimedia muestren y animen correctamente la barra de progreso.
     *
     * @param exoDurationMs duracion reportada por ExoPlayer (puede ser
     *   C.TIME_UNSET si aun no se conoce); se usa song.duration como
     *   respaldo.
     * @param isStillCurrent para no pisar la metadata si el usuario ya
     *   cambio de cancion mientras la caratula terminaba de cargar.
     * @param onArtReady se llama cuando la caratula ya esta lista y la
     *   MediaSession ya se actualizo con ella, para que MusicService pueda
     *   refrescar la notificacion.
     */
    fun updateMediaMetadata(
        song: Song,
        exoDurationMs: Long?,
        isStillCurrent: () -> Boolean,
        onArtReady: () -> Unit
    ) {
        val duration = if (exoDurationMs != null && exoDurationMs != C.TIME_UNSET && exoDurationMs > 0) {
            exoDurationMs
        } else {
            song.duration
        }

        // Publica de inmediato titulo/artista/duracion sin caratula (para
        // que el banner y la pantalla de bloqueo no se queden en blanco
        // mientras se busca la imagen), y la agrega en cuanto este lista.
        currentAlbumArt = null
        mediaSession.setMetadata(buildMetadata(song, duration, null))

        AlbumArtRepository.loadCover(
            context,
            song,
            object : AlbumArtRepository.Callback {
                override fun onCoverReady(bitmap: Bitmap) {
                    if (!isStillCurrent()) return
                    currentAlbumArt = bitmap
                    mediaSession.setMetadata(buildMetadata(song, duration, bitmap))
                    onArtReady()
                }
            },
            isStillNeeded = isStillCurrent
        )
    }

    private fun buildMetadata(song: Song, duration: Long, art: Bitmap?): MediaMetadataCompat {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        if (art != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
        }
        return builder.build()
    }

    fun updatePlaybackState(isPlaying: Boolean, positionMs: Long) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, positionMs, 1f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(context, MusicService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(context, action.hashCode(), intent, flags)
    }

    /**
     * Intent para abrir la app al tocar el banner/notificacion de
     * reproduccion (pantalla de bloqueo, control multimedia del sistema,
     * quick settings). Usa FLAG_ACTIVITY_CLEAR_TOP para volver a la
     * instancia existente de SongListActivity en vez de crear una nueva.
     */
    fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, SongListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    fun release() {
        mediaSession.isActive = false
        mediaSession.release()
    }
}