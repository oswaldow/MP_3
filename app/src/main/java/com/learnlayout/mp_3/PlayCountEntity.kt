package com.learnlayout.mp_3

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "play_counts")
data class PlayCountEntity(
    @PrimaryKey val songId: Long,
    val playCount: Int,
    val lastPlayedAt: Long
)