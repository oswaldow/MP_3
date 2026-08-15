package com.learnlayout.mp_3

/**
 * Guarda cuantas veces se reprodujo cada cancion y cuando fue la ultima vez.
 * Con estos dos datos se arman las playlists automaticas "Recientes" y
 * "Mas escuchadas" (ver SongListActivity.buildAutoPlaylists()).
 */
object PlayCountRepository {

    private fun dao(context: android.content.Context): PlayCountDao {
        LegacyDataMigrator.migrateIfNeeded(context)
        return AppDatabase.getInstance(context).playCountDao()
    }

    fun getPlayCount(context: android.content.Context, songId: Long): Int {
        return dao(context).getEntity(songId)?.playCount ?: 0
    }

    fun getLastPlayedAt(context: android.content.Context, songId: Long): Long {
        return dao(context).getEntity(songId)?.lastPlayedAt ?: 0L
    }

    fun getAllPlayCounts(context: android.content.Context): Map<Long, Int> {
        return dao(context).getAllEntities().associate { it.songId to it.playCount }
    }

    fun incrementPlayCount(context: android.content.Context, songId: Long) {
        val d = dao(context)
        val current = d.getEntity(songId)
        d.upsert(
            PlayCountEntity(
                songId = songId,
                playCount = (current?.playCount ?: 0) + 1,
                lastPlayedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * IDs de canciones reproducidas al menos una vez, ordenadas de la mas
     * escuchada a la menos escuchada. Sirve como base de la playlist
     * automatica "Mas escuchadas".
     */
    fun getMostPlayedSongIds(context: android.content.Context, limit: Int = 50): List<Long> {
        return dao(context).getMostPlayedSongIds(limit)
    }

    /**
     * IDs de canciones reproducidas, ordenadas de la mas reciente a la mas
     * antigua. Sirve como base de la playlist automatica "Recientes".
     */
    fun getRecentlyPlayedSongIds(context: android.content.Context, limit: Int = 50): List<Long> {
        return dao(context).getRecentlyPlayedSongIds(limit)
    }
}