package com.learnlayout.mp_3

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat


class PlaylistDialogs(
    private val context: Context,
    private val isPlaylistsTabActive: () -> Boolean,
    private val onPlaylistsChanged: () -> Unit,
    private val onSongMetadataChanged: () -> Unit,
    // Se llama luego de que el usuario confirma que quiere borrar la
    // cancion del dispositivo (no solo de la app). Quien construye este
    // dialogo (la Activity) es quien de verdad ejecuta el borrado, porque
    // necesita permisos/IntentSender que solo una Activity puede pedir.
    private val onDeleteSongFromDevice: (Song) -> Unit
) {

    fun showCreatePlaylistDialog(songIdToAdd: Long?) {
        val input = EditText(context)
        input.hint = "Nombre de la playlist"
        input.setTextColor(ContextCompat.getColor(context, R.color.text_primary_light))
        input.setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary_light))

        AlertDialog.Builder(context, R.style.RoundedAlertDialog)
            .setTitle("Nueva playlist")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val playlist = PlaylistRepository.createPlaylist(context, name)
                    if (songIdToAdd != null) {
                        PlaylistRepository.addSongToPlaylist(context, playlist.id, songIdToAdd)
                        Toast.makeText(context, "Cancion agregada a \"$name\"", Toast.LENGTH_SHORT).show()
                    }
                    if (isPlaylistsTabActive()) {
                        onPlaylistsChanged()
                    }
                } else {
                    Toast.makeText(context, "El nombre no puede estar vacio", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showSongItemMenu(song: Song) {
        val options = arrayOf("Agregar a playlist", "Editar nombre y artista", "Eliminar del dispositivo")
        AlertDialog.Builder(context, R.style.RoundedAlertDialog)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddToPlaylistDialog(song)
                    1 -> showEditSongMetadataDialog(song)
                    2 -> confirmDeleteSongFromDevice(song)
                }
            }
            .show()
    }

    /**
     * Confirmacion extra antes de borrar: a diferencia de "Quitar de la
     * playlist", esto borra el archivo de audio real del dispositivo, no
     * solo la referencia dentro de la app. Solo si el usuario confirma se
     * llama a [onDeleteSongFromDevice], que ejecuta el borrado real.
     */
    fun confirmDeleteSongFromDevice(song: Song) {
        AlertDialog.Builder(context, R.style.RoundedAlertDialog)
            .setTitle("Eliminar del dispositivo")
            .setMessage(
                "\"${song.title}\" se eliminara permanentemente de tu dispositivo, " +
                        "no solo de esta app. Esta accion no se puede deshacer. Continuar?"
            )
            .setPositiveButton("Eliminar") { _, _ ->
                onDeleteSongFromDevice(song)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showEditSongMetadataDialog(song: Song) {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        val padding = (20 * density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val titleInput = EditText(context)
        titleInput.hint = "Titulo"
        titleInput.setText(song.title)
        titleInput.setTextColor(ContextCompat.getColor(context, R.color.text_primary_light))
        titleInput.setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary_light))
        container.addView(titleInput)

        val artistInput = EditText(context)
        artistInput.hint = "Artista"
        artistInput.setText(song.artist)
        artistInput.setTextColor(ContextCompat.getColor(context, R.color.text_primary_light))
        artistInput.setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary_light))
        val artistParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        artistParams.topMargin = (12 * density).toInt()
        container.addView(artistInput, artistParams)

        AlertDialog.Builder(context, R.style.RoundedAlertDialog)
            .setTitle("Editar nombre y artista")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val newTitle = titleInput.text.toString().trim()
                val newArtist = artistInput.text.toString().trim()
                if (newTitle.isBlank() || newArtist.isBlank()) {
                    Toast.makeText(context, "Titulo y artista no pueden estar vacios", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                SongMetadataRepository.setOverride(context, song.id, newTitle, newArtist)
                onSongMetadataChanged()
                Toast.makeText(context, "Cancion actualizada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showAddToPlaylistDialog(song: Song) {
        val playlists = PlaylistRepository.getAllPlaylists(context)
        val options = playlists.map { it.name }.toMutableList()
        options.add("+ Crear nueva playlist")

        AlertDialog.Builder(context, R.style.RoundedAlertDialog)
            .setTitle("Agregar a playlist")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == playlists.size) {
                    showCreatePlaylistDialog(song.id)
                } else {
                    val playlist = playlists[which]
                    PlaylistRepository.addSongToPlaylist(context, playlist.id, song.id)
                    Toast.makeText(
                        context,
                        "Agregada a \"${playlist.name}\"",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    fun confirmDeletePlaylist(playlist: Playlist) {
        AlertDialog.Builder(context, R.style.RoundedAlertDialog)
            .setTitle("Eliminar playlist")
            .setMessage("Eliminar \"${playlist.name}\"? Esta accion no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                PlaylistRepository.deletePlaylist(context, playlist.id)
                onPlaylistsChanged()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}