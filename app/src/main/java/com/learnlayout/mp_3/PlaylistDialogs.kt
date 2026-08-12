package com.learnlayout.mp_3

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat


class PlaylistDialogs(
    private val context: Context,
    private val isPlaylistsTabActive: () -> Boolean,
    private val onPlaylistsChanged: () -> Unit,
    private val onSongMetadataChanged: () -> Unit,

    // Se llama luego de que el usuario confirma que quiere borrar la
    // cancion del dispositivo (no solo de la app).
    // Quien construye este dialogo (la Activity) es quien de verdad
    // ejecuta el borrado, porque necesita permisos/IntentSender
    // que solo una Activity puede pedir.
    private val onDeleteSongFromDevice: (Song) -> Unit
) {

    fun showCreatePlaylistDialog(songIdToAdd: Long?) {
        val input = EditText(context)

        input.hint = "Nombre de la playlist"

        input.setTextColor(
            ContextCompat.getColor(
                context,
                R.color.text_primary_light
            )
        )

        input.setHintTextColor(
            ContextCompat.getColor(
                context,
                R.color.text_secondary_light
            )
        )

        AlertDialog.Builder(
            context,
            R.style.RoundedAlertDialog
        )
            .setTitle("Nueva playlist")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->

                val name = input.text.toString().trim()

                if (name.isNotEmpty()) {

                    val playlist = PlaylistRepository.createPlaylist(
                        context,
                        name
                    )

                    if (songIdToAdd != null) {

                        PlaylistRepository.addSongToPlaylist(
                            context,
                            playlist.id,
                            songIdToAdd
                        )

                        Toast.makeText(
                            context,
                            "Cancion agregada a \"$name\"",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    if (isPlaylistsTabActive()) {
                        onPlaylistsChanged()
                    }

                } else {

                    Toast.makeText(
                        context,
                        "El nombre no puede estar vacio",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }


    /**
     * Menu de opciones de una cancion.
     *
     * Cada opcion tiene ahora:
     * - Su propio borde morado.
     * - Fondo oscuro.
     * - Esquinas redondeadas.
     * - Separacion entre opciones.
     *
     * Opciones:
     * 1. Agregar a playlist
     * 2. Editar nombre y artista
     * 3. Eliminar del dispositivo
     */
    fun showSongItemMenu(song: Song) {

        val density = context.resources.displayMetrics.density

        val options = listOf(
            "Agregar a playlist" to {
                showAddToPlaylistDialog(song)
            },

            "Editar nombre y artista" to {
                showEditSongMetadataDialog(song)
            },

            "Eliminar del dispositivo" to {
                confirmDeleteSongFromDevice(song)
            }
        )

        /*
         * Contenedor principal.
         */
        val container = LinearLayout(context)

        container.orientation = LinearLayout.VERTICAL

        container.setPadding(
            (14 * density).toInt(),
            (8 * density).toInt(),
            (14 * density).toInt(),
            (8 * density).toInt()
        )

        /*
         * Necesitamos tener acceso al dialogo desde el click
         * de cada opcion.
         */
        lateinit var dialog: AlertDialog

        /*
         * Color del borde.
         */
        val purpleColor = ContextCompat.getColor(
            context,
            R.color.purple_primary
        )

        /*
         * Color de fondo de cada opcion.
         *
         * Se intenta utilizar el color existente de la aplicacion.
         * Si no existe en tu proyecto, cambia esta parte por:
         *
         * android.graphics.Color.rgb(18, 18, 18)
         */
        val backgroundColor = try {
            ContextCompat.getColor(
                context,
                R.color.spotify_black
            )
        } catch (e: Exception) {
            android.graphics.Color.rgb(18, 18, 18)
        }

        /*
         * Ripple nativo de Android.
         */
        val rippleBackground = TypedValue()

        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            rippleBackground,
            true
        )

        /*
         * Crear individualmente las tres opciones.
         */
        options.forEachIndexed { index, (label, action) ->

            /*
             * TextView que funciona como boton.
             */
            val row = TextView(context)

            row.text = label

            row.textSize = 16f

            row.setTextColor(
                ContextCompat.getColor(
                    context,
                    R.color.text_primary_light
                )
            )

            row.gravity = Gravity.CENTER_VERTICAL

            row.setPadding(
                (20 * density).toInt(),
                (16 * density).toInt(),
                (20 * density).toInt(),
                (16 * density).toInt()
            )

            row.isClickable = true
            row.isFocusable = true

            /*
             * Fondo personalizado.
             *
             * Cada opcion queda encerrada individualmente
             * por un borde morado.
             */
            val optionBackground = GradientDrawable().apply {

                shape = GradientDrawable.RECTANGLE

                /*
                 * Fondo oscuro.
                 */
                setColor(backgroundColor)

                /*
                 * Borde morado.
                 */
                setStroke(
                    (1.5f * density).toInt(),
                    purpleColor
                )

                /*
                 * Esquinas redondeadas.
                 */
                cornerRadius = 12f * density
            }

            row.background = optionBackground

            /*
             * Ripple al presionar.
             *
             * No reemplazamos el fondo porque necesitamos
             * conservar el borde morado.
             */
            if (rippleBackground.resourceId != 0) {
                row.foreground = ContextCompat.getDrawable(
                    context,
                    rippleBackground.resourceId
                )
            }

            /*
             * Accion al pulsar.
             */
            row.setOnClickListener {

                dialog.dismiss()

                action()
            }

            /*
             * Parametros de cada opcion.
             */
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            /*
             * Espacio entre cada cuadro.
             */
            if (index > 0) {
                rowParams.topMargin = (8 * density).toInt()
            }

            container.addView(
                row,
                rowParams
            )
        }

        /*
         * Crear el dialogo.
         */
        dialog = AlertDialog.Builder(
            context,
            R.style.RoundedAlertDialog
        )
            .setView(container)
            .create()

        dialog.show()
    }


    /**
     * Confirmacion extra antes de borrar.
     *
     * A diferencia de "Quitar de la playlist", esto borra
     * el archivo de audio real del dispositivo, no solo
     * la referencia dentro de la app.
     */
    fun confirmDeleteSongFromDevice(song: Song) {

        AlertDialog.Builder(
            context,
            R.style.RoundedAlertDialog
        )
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

        container.setPadding(
            padding,
            padding,
            padding,
            padding
        )

        /*
         * Campo del titulo.
         */
        val titleInput = EditText(context)

        titleInput.hint = "Titulo"

        titleInput.setText(song.title)

        titleInput.setTextColor(
            ContextCompat.getColor(
                context,
                R.color.text_primary_light
            )
        )

        titleInput.setHintTextColor(
            ContextCompat.getColor(
                context,
                R.color.text_secondary_light
            )
        )

        container.addView(titleInput)

        /*
         * Campo del artista.
         */
        val artistInput = EditText(context)

        artistInput.hint = "Artista"

        artistInput.setText(song.artist)

        artistInput.setTextColor(
            ContextCompat.getColor(
                context,
                R.color.text_primary_light
            )
        )

        artistInput.setHintTextColor(
            ContextCompat.getColor(
                context,
                R.color.text_secondary_light
            )
        )

        val artistParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        artistParams.topMargin = (12 * density).toInt()

        container.addView(
            artistInput,
            artistParams
        )

        /*
         * Dialogo para editar.
         */
        AlertDialog.Builder(
            context,
            R.style.RoundedAlertDialog
        )
            .setTitle("Editar nombre y artista")

            .setView(container)

            .setPositiveButton("Guardar") { _, _ ->

                val newTitle = titleInput
                    .text
                    .toString()
                    .trim()

                val newArtist = artistInput
                    .text
                    .toString()
                    .trim()

                if (
                    newTitle.isBlank() ||
                    newArtist.isBlank()
                ) {

                    Toast.makeText(
                        context,
                        "Titulo y artista no pueden estar vacios",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setPositiveButton
                }

                SongMetadataRepository.setOverride(
                    context,
                    song.id,
                    newTitle,
                    newArtist
                )

                onSongMetadataChanged()

                Toast.makeText(
                    context,
                    "Cancion actualizada",
                    Toast.LENGTH_SHORT
                ).show()
            }

            .setNegativeButton(
                "Cancelar",
                null
            )

            .show()
    }


    fun showAddToPlaylistDialog(song: Song) {

        val playlists =
            PlaylistRepository.getAllPlaylists(context)

        val options =
            playlists
                .map { it.name }
                .toMutableList()

        options.add("+ Crear nueva playlist")

        AlertDialog.Builder(
            context,
            R.style.RoundedAlertDialog
        )
            .setTitle("Agregar a playlist")

            .setItems(
                options.toTypedArray()
            ) { _, which ->

                if (which == playlists.size) {

                    showCreatePlaylistDialog(song.id)

                } else {

                    val playlist = playlists[which]

                    PlaylistRepository.addSongToPlaylist(
                        context,
                        playlist.id,
                        song.id
                    )

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

        AlertDialog.Builder(
            context,
            R.style.RoundedAlertDialog
        )
            .setTitle("Eliminar playlist")

            .setMessage(
                "Eliminar \"${playlist.name}\"? " +
                        "Esta accion no se puede deshacer."
            )

            .setPositiveButton("Eliminar") { _, _ ->

                PlaylistRepository.deletePlaylist(
                    context,
                    playlist.id
                )

                onPlaylistsChanged()
            }

            .setNegativeButton(
                "Cancelar",
                null
            )

            .show()
    }
}