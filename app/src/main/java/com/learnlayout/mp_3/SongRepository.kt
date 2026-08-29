package com.learnlayout.mp_3

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

object SongRepository {

    private const val MIN_DURATION_MS = 30000

    fun getAllSongs(context: Context): List<Song> {
        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
                " AND ${MediaStore.Audio.Media.DURATION} >= ?" +
                " AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0" +
                " AND ${MediaStore.Audio.Media.IS_ALARM} = 0" +
                " AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0" +
                " AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?" +
                " AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?" +
                " AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?" +
                " AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?" +
                " AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?" +
                " AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?"

        val selectionArgs = arrayOf(
            MIN_DURATION_MS.toString(),
            "%WhatsApp%",
            "%Notifications%",
            "%Ringtones%",
            "%mojang%",
            "%Minecraft%",
            "%Android/data%"
        )

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val cursor = context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val title = it.getString(titleColumn) ?: "Desconocido"
                val rawArtist = it.getString(artistColumn) ?: "Desconocido"
                val rawAlbum = it.getString(albumColumn) ?: ""
                val artist = stripNoiseFromArtist(title, rawAlbum, rawArtist)
                val duration = it.getLong(durationColumn)
                val dateAdded = it.getLong(dateAddedColumn)
                val dateModified = it.getLong(dateModifiedColumn)

                // DATE_ADDED no siempre refleja cuando el archivo llego de
                // verdad al telefono: muchas apps (WhatsApp, Telegram,
                // navegadores) y sobre todo MIUI/HyperOS preservan la fecha
                // de origen del archivo en esa columna, asi que una cancion
                // descargada hoy puede aparecer con una fecha de hace
                // semanas. DATE_MODIFIED si se actualiza al momento real en
                // que el archivo se escribio en el almacenamiento del
                // dispositivo, asi que usamos la mas reciente de las dos
                // como "fecha de agregado" real para ordenar.
                val effectiveDateAdded = maxOf(dateAdded, dateModified)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                songs.add(Song(id, title, artist, duration, contentUri, effectiveDateAdded))
            }
        }

        return songs
    }

    /**
     * Algunas canciones (sobre todo descargadas de redes sociales, no
     * etiquetadas por una disquera) traen mal puesto el tag ARTISTA: el
     * propio nombre de la cancion o del album quedan pegados ahi junto a
     * los artistas reales, por ejemplo:
     *   title  = "G Low Kitty (Remix)"
     *   artist = "G Low Kitty (Remix), El Bogueto, J Balvin"
     * o directamente el nombre del album metido junto a los artistas.
     *
     * Esto no es solo un problema de que se vea feo en la lista: el
     * artista "sucio" tambien se manda tal cual a LyricsRepository.fetch
     * (LRCLIB) para buscar la letra, y ese ruido hace que la busqueda no
     * encuentre nada aunque la cancion si este en su catalogo. Por eso
     * esta limpieza se hace aca, en el mismo lugar donde se leen los
     * tags crudos de MediaStore, para que tanto la lista/reproductor
     * como la busqueda de letra usen el mismo artista ya limpio.
     *
     * El titulo/album y el artista nunca deberian ser lo mismo, asi que
     * se quita del artista cualquier fragmento que coincida con
     * cualquiera de los dos (ver [removeNoiseTerm]).
     */
    private fun stripNoiseFromArtist(title: String, album: String, artist: String): String {
        if (artist.isBlank()) {
            return artist
        }

        var result = artist

        val cleanTitle = title.trim()
        if (cleanTitle.isNotEmpty()) {
            result = removeNoiseTerm(result, cleanTitle)
        }

        val cleanAlbum = album.trim()
        if (cleanAlbum.isNotEmpty() && !isGenericAlbumName(cleanAlbum)) {
            result = removeNoiseTerm(result, cleanAlbum)
        }

        return result
    }

    /**
     * Quita [noise] (el titulo o el album) de [artist].
     *
     * Se intenta primero por partes (el caso mas comun: el artista es
     * una lista separada por comas, y [noise] aparece como una entrada
     * completa de esa lista). Si no aparece asi -por ejemplo, viene
     * pegado sin comas a los artistas reales- se cae a un reemplazo
     * simple del texto dentro de lo que haya quedado, seguido de una
     * limpieza de separadores sueltos (comas/guiones duplicados, al
     * inicio o al final).
     *
     * Si al quitarlo no queda ningun artista real, se prefiere conservar
     * el valor de entrada tal cual venia (mejor mostrar algo redundante
     * que un campo vacio).
     */
    private fun removeNoiseTerm(artist: String, noise: String): String {
        val parts = artist.split(",").map { it.trim() }

        val filteredParts = parts.filterNot {
            it.equals(noise, ignoreCase = true)
        }

        var candidate = if (filteredParts.size != parts.size) {
            filteredParts.filter { it.isNotEmpty() }.joinToString(", ").ifBlank { artist }
        } else {
            artist
        }

        if (candidate.contains(noise, ignoreCase = true)) {
            val withoutNoise = candidate
                .replace(noise, "", ignoreCase = true)
                .replace(Regex("[,\\-–—/]+"), ",")
                .trim(' ', ',', '-', '–', '—', '/')
                .replace(Regex("\\s{2,}"), " ")
                .trim()

            if (withoutNoise.isNotBlank()) {
                candidate = withoutNoise
            }
        }

        return candidate
    }

    /**
     * Nombres de album genericos que MediaStore devuelve cuando el
     * archivo no trae un album real etiquetado. Quitar estos del
     * artista podria borrar coincidencias legitimas (poco probable,
     * pero por ejemplo un artista llamado literal "Desconocido"), asi
     * que se ignoran para efectos de esta limpieza.
     */
    private fun isGenericAlbumName(album: String): Boolean {
        return album.equals("<unknown>", ignoreCase = true) ||
                album.equals("unknown", ignoreCase = true) ||
                album.equals("unknown album", ignoreCase = true) ||
                album.equals("desconocido", ignoreCase = true)
    }
}