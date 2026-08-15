package com.learnlayout.mp_3

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlayCountDao {

    @Query("SELECT * FROM play_counts WHERE songId = :songId LIMIT 1")
    fun getEntity(songId: Long): PlayCountEntity?

    @Query("SELECT * FROM play_counts")
    fun getAllEntities(): List<PlayCountEntity>

    @Query("SELECT COUNT(*) FROM play_counts")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: PlayCountEntity)

    @Query("SELECT songId FROM play_counts WHERE playCount > 0 ORDER BY playCount DESC LIMIT :limit")
    fun getMostPlayedSongIds(limit: Int): List<Long>

    @Query("SELECT songId FROM play_counts WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun getRecentlyPlayedSongIds(limit: Int): List<Long>
}