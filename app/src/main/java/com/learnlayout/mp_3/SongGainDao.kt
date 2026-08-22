package com.learnlayout.mp_3

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SongGainDao {

    @Query("SELECT * FROM song_gains WHERE songId = :songId LIMIT 1")
    fun getGain(songId: Long): SongGainEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGain(entity: SongGainEntity)
}