package com.learnlayout.mp_3

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ganancia de normalizacion de volumen calculada para una cancion (ver
 * LoudnessAnalyzer / SongGainRepository). Se guarda una sola vez por
 * cancion para no tener que reanalizar el audio en cada reproduccion.
 */
@Entity(tableName = "song_gains")
data class SongGainEntity(
    @PrimaryKey val songId: Long,
    val gainDb: Double
)