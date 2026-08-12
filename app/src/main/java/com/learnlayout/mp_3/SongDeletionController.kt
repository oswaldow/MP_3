package com.learnlayout.mp_3

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat

class SongDeletionController(
    private val activity: Activity,
    private val deleteSongIntentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>,
    private val writeStoragePermissionLauncher: ActivityResultLauncher<String>,
    private val onSongDeleted: (Song) -> Unit
) {

    private var pendingDeleteSong: Song? = null

    /**
     * Punto de entrada para eliminar una canción del dispositivo.
     *
     * Android 11+: confirmación mediante MediaStore.createDeleteRequest.
     * Android 10: eliminación directa o RecoverableSecurityException.
     * Android 9 o inferior: permiso WRITE_EXTERNAL_STORAGE.
     */
    fun requestDelete(song: Song) {
        pendingDeleteSong = song
        performDelete(song)
    }

    /**
     * Debe llamarse desde el resultado de StartIntentSenderForResult.
     */
    fun onDeleteIntentResult(resultCode: Int) {
        val song = pendingDeleteSong
        pendingDeleteSong = null

        if (resultCode == Activity.RESULT_OK && song != null) {
            onSongDeleted(song)
        } else {
            Toast.makeText(
                activity,
                "No se elimino la cancion",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Debe llamarse desde el resultado de RequestPermission para
     * WRITE_EXTERNAL_STORAGE.
     */
    fun onWriteStoragePermissionResult(granted: Boolean) {
        val song = pendingDeleteSong

        if (granted && song != null) {
            performDelete(song)
        } else {
            pendingDeleteSong = null

            Toast.makeText(
                activity,
                "Se necesita permiso para eliminar el archivo",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun performDelete(song: Song) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val pendingIntent = MediaStore.createDeleteRequest(
                    activity.contentResolver,
                    listOf(song.uri)
                )

                deleteSongIntentSenderLauncher.launch(
                    IntentSenderRequest.Builder(
                        pendingIntent.intentSender
                    ).build()
                )
            }

            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                try {
                    activity.contentResolver.delete(song.uri, null, null)
                    pendingDeleteSong = null
                    onSongDeleted(song)
                } catch (e: RecoverableSecurityException) {
                    deleteSongIntentSenderLauncher.launch(
                        IntentSenderRequest.Builder(
                            e.userAction.actionIntent.intentSender
                        ).build()
                    )
                }
            }

            else -> {
                if (
                    ContextCompat.checkSelfPermission(
                        activity,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        activity.contentResolver.delete(song.uri, null, null)
                        pendingDeleteSong = null
                        onSongDeleted(song)
                    } catch (e: SecurityException) {
                        pendingDeleteSong = null

                        Toast.makeText(
                            activity,
                            "No se pudo eliminar el archivo",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    writeStoragePermissionLauncher.launch(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                }
            }
        }
    }
}