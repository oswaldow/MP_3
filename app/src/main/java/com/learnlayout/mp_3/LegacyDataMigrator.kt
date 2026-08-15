package com.learnlayout.mp_3

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Migra datos de versiones anteriores del guardado (SharedPreferences
 * original y, si existio, la version intermedia con SafeJsonStore) hacia
 * las tablas de Room. Se corre una sola vez por proceso: si las tablas de
 * Room ya tienen datos, no hace nada.
 */
object LegacyDataMigrator {

    private const val TAG = "LegacyDataMigrator"

    private const val LEGACY_PLAYLISTS_PREFS = "playlists_prefs"
    private const val LEGACY_PLAYLISTS_KEY = "playlists_json"

    private const val LEGACY_PLAYCOUNT_PREFS = "play_count_prefs"
    private const val LEGACY_COUNTS_KEY = "play_counts_json"
    private const val LEGACY_LAST_PLAYED_KEY = "last_played_json"

    private const val SAFEJSON_PLAYLISTS_FILE = "playlists"
    private const val SAFEJSON_COUNTS_FILE = "play_counts"
    private const val SAFEJSON_LAST_PLAYED_FILE = "last_played"

    @Volatile private var didRun = false

    @Synchronized
    fun migrateIfNeeded(context: Context) {
        if (didRun) return
        didRun = true

        val db = AppDatabase.getInstance(context)
        migratePlaylists(context, db)
        migratePlayCounts(context, db)
    }

    private fun migratePlaylists(context: Context, db: AppDatabase) {
        val dao = db.playlistDao()
        if (dao.countPlaylists() > 0) return

        val json = readLegacyJson(
            context,
            safeJsonFileName = SAFEJSON_PLAYLISTS_FILE,
            legacyPrefsName = LEGACY_PLAYLISTS_PREFS,
            legacyKey = LEGACY_PLAYLISTS_KEY
        ) ?: return

        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val cover = obj.optString("cover", "").ifBlank { null }
                dao.insertPlaylist(
                    PlaylistEntity(id = id, name = name, coverImageUri = cover, sortOrder = i)
                )

                val songIdsArray = obj.getJSONArray("songIds")
                val refs = mutableListOf<PlaylistSongCrossRef>()
                for (j in 0 until songIdsArray.length()) {
                    refs.add(PlaylistSongCrossRef(playlistId = id, songId = songIdsArray.getLong(j), position = j))
                }
                if (refs.isNotEmpty()) dao.insertPlaylistSongs(refs)
            }
            Log.w(TAG, "Migradas ${array.length()} playlists a Room")
        } catch (e: Exception) {
            Log.e(TAG, "Error migrando playlists a Room, se dejan los datos viejos sin tocar", e)
        }
    }

    private fun migratePlayCounts(context: Context, db: AppDatabase) {
        val dao = db.playCountDao()
        if (dao.count() > 0) return

        val countsJson = readLegacyJson(
            context,
            safeJsonFileName = SAFEJSON_COUNTS_FILE,
            legacyPrefsName = LEGACY_PLAYCOUNT_PREFS,
            legacyKey = LEGACY_COUNTS_KEY
        )
        val lastPlayedJson = readLegacyJson(
            context,
            safeJsonFileName = SAFEJSON_LAST_PLAYED_FILE,
            legacyPrefsName = LEGACY_PLAYCOUNT_PREFS,
            legacyKey = LEGACY_LAST_PLAYED_KEY
        )
        if (countsJson == null && lastPlayedJson == null) return

        try {
            val counts = HashMap<Long, Int>()
            val lastPlayed = HashMap<Long, Long>()

            countsJson?.let {
                val obj = JSONObject(it)
                obj.keys().forEach { key ->
                    key.toLongOrNull()?.let { id -> counts[id] = obj.optInt(key, 0) }
                }
            }
            lastPlayedJson?.let {
                val obj = JSONObject(it)
                obj.keys().forEach { key ->
                    key.toLongOrNull()?.let { id -> lastPlayed[id] = obj.optLong(key, 0L) }
                }
            }

            val allIds = counts.keys + lastPlayed.keys
            allIds.forEach { songId ->
                dao.upsert(
                    PlayCountEntity(
                        songId = songId,
                        playCount = counts[songId] ?: 0,
                        lastPlayedAt = lastPlayed[songId] ?: 0L
                    )
                )
            }
            Log.w(TAG, "Migrados ${allIds.size} contadores de reproduccion a Room")
        } catch (e: Exception) {
            Log.e(TAG, "Error migrando contadores de reproduccion a Room, se dejan los datos viejos sin tocar", e)
        }
    }

    /**
     * Intenta leer primero del archivo SafeJsonStore (version intermedia);
     * si no existe, cae al SharedPreferences original (version mas vieja).
     */
    private fun readLegacyJson(
        context: Context,
        safeJsonFileName: String,
        legacyPrefsName: String,
        legacyKey: String
    ): String? {
        val safeStore = SafeJsonStore(context, safeJsonFileName)
        if (safeStore.exists()) {
            val raw = safeStore.read(default = "")
            if (raw.isNotBlank()) return raw
        }

        val prefs = context.getSharedPreferences(legacyPrefsName, Context.MODE_PRIVATE)
        return prefs.getString(legacyKey, null)
    }
}