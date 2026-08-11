package com.learnlayout.mp_3

import android.content.Context
import org.json.JSONObject

/**
 * Guarda cuantas veces se reprodujo cada cancion y cuando fue la ultima vez.
 * Con estos dos datos se arman las playlists automaticas "Recientes" y
 * "Mas escuchadas" (ver SongListActivity.buildAutoPlaylists()).
 */
object PlayCountRepository {

    private const val PREFS_NAME = "play_count_prefs"
    private const val KEY_COUNTS = "play_counts_json"
    private const val KEY_LAST_PLAYED = "last_played_json"

    fun getPlayCount(context: Context, songId: Long): Int {
        return getJson(context, KEY_COUNTS).optInt(songId.toString(), 0)
    }

    fun getLastPlayedAt(context: Context, songId: Long): Long {
        return getJson(context, KEY_LAST_PLAYED).optLong(songId.toString(), 0L)
    }

    fun getAllPlayCounts(context: Context): Map<Long, Int> {
        val counts = getJson(context, KEY_COUNTS)
        val map = HashMap<Long, Int>(counts.length())
        counts.keys().asSequence().forEach { key ->
            key.toLongOrNull()?.let { id -> map[id] = counts.optInt(key, 0) }
        }
        return map
    }
    fun incrementPlayCount(context: Context, songId: Long) {
        val counts = getJson(context, KEY_COUNTS)
        val current = counts.optInt(songId.toString(), 0)
        counts.put(songId.toString(), current + 1)
        saveJson(context, KEY_COUNTS, counts)

        val lastPlayed = getJson(context, KEY_LAST_PLAYED)
        lastPlayed.put(songId.toString(), System.currentTimeMillis())
        saveJson(context, KEY_LAST_PLAYED, lastPlayed)
    }

    /**
     * IDs de canciones reproducidas al menos una vez, ordenadas de la mas
     * escuchada a la menos escuchada. Sirve como base de la playlist
     * automatica "Mas escuchadas".
     */
    fun getMostPlayedSongIds(context: Context, limit: Int = 50): List<Long> {
        val counts = getJson(context, KEY_COUNTS)
        return counts.keys().asSequence()
            .mapNotNull { key -> key.toLongOrNull()?.let { it to counts.optInt(key, 0) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * IDs de canciones reproducidas, ordenadas de la mas reciente a la mas
     * antigua. Sirve como base de la playlist automatica "Recientes".
     */
    fun getRecentlyPlayedSongIds(context: Context, limit: Int = 50): List<Long> {
        val lastPlayed = getJson(context, KEY_LAST_PLAYED)
        return lastPlayed.keys().asSequence()
            .mapNotNull { key -> key.toLongOrNull()?.let { it to lastPlayed.optLong(key, 0L) } }
            .filter { it.second > 0L }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun getJson(context: Context, key: String): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(key, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveJson(context: Context, key: String, json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, json.toString()).apply()
    }
}