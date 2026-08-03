package com.learnlayout.mp_3

data class Playlist(
    val id: String,
    var name: String,
    val songIds: MutableList<Long>,
    var coverImageUri: String? = null
)