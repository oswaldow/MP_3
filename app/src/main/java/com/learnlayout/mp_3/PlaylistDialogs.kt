package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
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

    /**
     * Aplica el mismo look de "caja redondeada con borde morado" que
     * ya usa el buscador (bg_search_box) a los EditText de estos
     * dialogos. Antes se quedaban con el subrayado gris por defecto
     * de Android, que no combina con el resto de la app.
     */
    private fun styleDialogInput(input: EditText) {

        val density = context.resources.displayMetrics.density

        input.background = ContextCompat.getDrawable(
            context,
            R.drawable.bg_search_box
        )

        input.setPadding(
            (16 * density).toInt(),
            (12 * density).toInt(),
            (16 * density).toInt(),
            (12 * density).toInt()
        )

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
    }


    fun showCreatePlaylistDialog(songIdToAdd: Long?) {
        val density = context.resources.displayMetrics.density

        val input = EditText(context)

        input.hint = "Nombre de la playlist"

        styleDialogInput(input)

        val container = LinearLayout(context)

        container.orientation = LinearLayout.VERTICAL

        container.setPadding(
            (20 * density).toInt(),
            (4 * density).toInt(),
            (20 * density).toInt(),
            0
        )

        container.addView(input)

        AlertDialog.Builder(
            context,
            R.style.RoundedAlertDialog
        )
            .setTitle("Nueva playlist")
            .setView(container)
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
     * Antes cada opcion era su propia caja individual con borde
     * morado grueso, flotando separada de las demas: se veia como
     * tres elementos sueltos en vez de un solo menu. Ahora es una
     * unica tarjeta (el propio fondo redondeado del dialogo, igual
     * que el resto de los AlertDialog de la app) con filas dentro,
     * separadas por una linea delgada, cada una con su icono y
     * ripple al tocar, igual que las filas de la lista de canciones.
     *
     * Opciones:
     * 1. Agregar a playlist
     * 2. Editar nombre y artista
     * 3. Eliminar del dispositivo (destructiva, en rojo)
     */
    fun showSongItemMenu(song: Song) {

        val density = context.resources.displayMetrics.density

        data class MenuOption(
            val label: String,
            val iconRes: Int,
            val destructive: Boolean,
            val action: () -> Unit
        )

        val options = listOf(
            MenuOption(
                "Agregar a playlist",
                R.drawable.ic_add,
                false
            ) {
                showAddToPlaylistDialog(song)
            },

            MenuOption(
                "Editar nombre y artista",
                R.drawable.ic_edit,
                false
            ) {
                showEditSongMetadataDialog(song)
            },

            MenuOption(
                "Eliminar del dispositivo",
                R.drawable.ic_delete,
                true
            ) {
                confirmDeleteSongFromDevice(song)
            }
        )

        val destructiveColor = Color.parseColor("#F26161")

        val primaryColor = ContextCompat.getColor(
            context,
            R.color.text_primary_light
        )

        val secondaryColor = ContextCompat.getColor(
            context,
            R.color.text_secondary_light
        )

        val dividerColor = Color.parseColor("#1FFFFFFF")

        /*
         * Contenedor principal: sin fondo propio, usa el mismo
         * bg_dialog_rounded que ya trae el AlertDialog por su tema
         * (RoundedAlertDialog), para que se vea como una sola
         * tarjeta consistente con el resto de dialogos de la app.
         */
        val container = LinearLayout(context)

        container.orientation = LinearLayout.VERTICAL

        container.setPadding(
            0,
            (4 * density).toInt(),
            0,
            (4 * density).toInt()
        )

        /*
         * Necesitamos tener acceso al dialogo desde el click
         * de cada opcion.
         */
        lateinit var dialog: AlertDialog

        /*
         * Ripple nativo de Android.
         */
        val rippleBackground = TypedValue()

        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            rippleBackground,
            true
        )

        options.forEachIndexed { index, option ->

            /*
             * Separador delgado entre opciones (no antes de la
             * primera).
             */
            if (index > 0) {

                val divider = TextView(context)

                divider.setBackgroundColor(dividerColor)

                container.addView(
                    divider,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt()
                    ).apply {
                        leftMargin = (20 * density).toInt()
                        rightMargin = (20 * density).toInt()
                    }
                )
            }

            /*
             * Fila: icono + texto.
             */
            val row = LinearLayout(context)

            row.orientation = LinearLayout.HORIZONTAL

            row.gravity = Gravity.CENTER_VERTICAL

            row.setPadding(
                (20 * density).toInt(),
                (15 * density).toInt(),
                (20 * density).toInt(),
                (15 * density).toInt()
            )

            row.isClickable = true

            row.isFocusable = true

            if (rippleBackground.resourceId != 0) {
                row.background = ContextCompat.getDrawable(
                    context,
                    rippleBackground.resourceId
                )
            }

            val icon = ImageView(context)

            icon.setImageResource(option.iconRes)

            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (option.destructive) destructiveColor else secondaryColor
            )

            row.addView(
                icon,
                LinearLayout.LayoutParams(
                    (20 * density).toInt(),
                    (20 * density).toInt()
                )
            )

            val label = TextView(context)

            label.text = option.label

            label.textSize = 15.5f

            label.setTextColor(
                if (option.destructive) destructiveColor else primaryColor
            )

            row.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (16 * density).toInt()
                }
            )

            row.setOnClickListener {

                dialog.dismiss()

                option.action()
            }

            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
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

        styleDialogInput(titleInput)

        container.addView(titleInput)

        /*
         * Campo del artista.
         */
        val artistInput = EditText(context)

        artistInput.hint = "Artista"

        artistInput.setText(song.artist)

        styleDialogInput(artistInput)

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