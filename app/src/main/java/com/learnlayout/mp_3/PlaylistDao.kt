package com.learnlayout.mp_3

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC")
    fun getAllPlaylistEntities(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    fun getPlaylistEntity(playlistId: String): PlaylistEntity?

    @Query("SELECT COUNT(*) FROM playlists")
    fun countPlaylists(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlists")
    fun getMaxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylist(playlist: PlaylistEntity)

    @Update
    fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    fun deletePlaylist(playlistId: String)

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getSongIdsForPlaylist(playlistId: String): List<Long>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    fun getMaxPosition(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylistSong(ref: PlaylistSongCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylistSongs(refs: List<PlaylistSongCrossRef>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    fun removeSongFromPlaylist(playlistId: String, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE songId = :songId")
    fun removeSongFromAllPlaylists(songId: Long)
}