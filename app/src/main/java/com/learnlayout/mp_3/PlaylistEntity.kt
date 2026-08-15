package com.learnlayout.mp_3

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverImageUri: String?,
    // Orden de aparicion en la lista. Favoritos usa -1 para ir siempre primero.
    val sortOrder: Int
)