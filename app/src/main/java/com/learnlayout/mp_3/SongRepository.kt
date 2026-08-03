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
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED
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
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val title = it.getString(titleColumn) ?: "Desconocido"
                val artist = it.getString(artistColumn) ?: "Desconocido"
                val duration = it.getLong(durationColumn)
                val dateAdded = it.getLong(dateAddedColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                songs.add(Song(id, title, artist, duration, contentUri, dateAdded))
            }
        }

        return songs
    }
}