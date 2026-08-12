package com.learnlayout.mp_3

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder

/**
 * Mantiene la conexión con MusicService y una solicitud de reproducción
 * pendiente cuando el usuario intenta reproducir antes de que el servicio
 * termine de conectarse.
 */
class MusicServiceConnectionController(
    private val onServiceConnected: (MusicService, PendingPlayback?) -> Unit,
    private val onServiceDisconnected: () -> Unit
) {

    data class PendingPlayback(
        val songs: List<Song>,
        val startIndex: Int
    )

    private var pendingPlayback: PendingPlayback? = null

    val connection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            val musicService =
                (service as MusicService.MusicBinder).getService()

            val pending = pendingPlayback
            pendingPlayback = null

            onServiceConnected(musicService, pending)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onServiceDisconnected()
        }
    }

    fun queuePlayback(
        songs: List<Song>,
        startIndex: Int
    ) {
        pendingPlayback = PendingPlayback(
            songs = songs,
            startIndex = startIndex
        )
    }
}