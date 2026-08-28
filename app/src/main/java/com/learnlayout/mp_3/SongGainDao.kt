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

    @Query("SELECT songId FROM song_gains")
    fun getAllSongIds(): List<Long>

    @Query("DELETE FROM song_gains WHERE songId = :songId")
    fun deleteGain(songId: Long)

    // Si ya existia un registro para newId, choca con la primary key
    // (songId) y lanza SQLiteConstraintException; SongIdMigrator la
    // captura y sigue con las demas tablas sin problema.
    @Query("UPDATE song_gains SET songId = :newId WHERE songId = :oldId")
    fun remapSongId(oldId: Long, newId: Long)
}