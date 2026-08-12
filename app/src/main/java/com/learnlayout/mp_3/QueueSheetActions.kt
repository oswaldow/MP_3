package com.learnlayout.mp_3

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Acciones que no pertenecen a la UI de la cola como tal.
 * Mantenerlas fuera del controller evita mezclar edición de playlists
 * con el ciclo de vida del diálogo de la cola.
 */
class QueueSheetActions(
    private val activity: AppCompatActivity
) {

    fun saveQueueAsPlaylist(songs: List<Song>) {
        if (songs.isEmpty()) return

        val input = EditText(activity).apply {
            hint = "Nombre de la playlist"
            inputType =
                InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }

        val container = android.widget.FrameLayout(activity).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(
                input,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(
            activity,
            R.style.RoundedAlertDialog
        )
            .setTitle("Guardar cola")
            .setMessage(
                "Guarda las ${songs.size} canciones actuales como una nueva playlist."
            )
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()

                if (name.isBlank()) {
                    Toast.makeText(
                        activity,
                        "Escribe un nombre para la playlist",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                val playlist = PlaylistRepository.createPlaylist(
                    activity,
                    name
                )

                songs.forEach { song ->
                    PlaylistRepository.addSongToPlaylist(
                        activity,
                        playlist.id,
                        song.id
                    )
                }

                Toast.makeText(
                    activity,
                    "Playlist \"$name\" guardada",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun dp(value: Int): Int {
        return (
                value * activity.resources.displayMetrics.density
                ).toInt()
    }
}