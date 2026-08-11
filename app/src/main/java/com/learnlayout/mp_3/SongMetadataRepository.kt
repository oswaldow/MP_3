package com.learnlayout.mp_3

import android.content.Context
import org.json.JSONObject

/**
 * Overrides locales de titulo/artista por cancion, para cuando el nombre
 * del archivo no sirve para buscar la letra. No toca el archivo de audio.
 */
object SongMetadataRepository {

    private const val PREFS_NAME = "song_metadata_prefs"
    private const val KEY_OVERRIDES = "overrides_json"

    fun setOverride(context: Context, songId: Long, title: String, artist: String) {
        val all = readAll(context)
        val obj = JSONObject()
        obj.put("title", title)
        obj.put("artist", artist)
        all.put(songId.toString(), obj)
        prefs(context).edit().putString(KEY_OVERRIDES, all.toString()).apply()
    }

    /**
     * Quita el override de titulo/artista de [songId]. Se usa cuando la
     * cancion se borra del dispositivo, para no dejar datos huerfanos de
     * un archivo que ya no existe.
     */
    fun removeOverride(context: Context, songId: Long) {
        val all = readAll(context)
        if (all.has(songId.toString())) {
            all.remove(songId.toString())
            prefs(context).edit().putString(KEY_OVERRIDES, all.toString()).apply()
        }
    }

    fun apply(context: Context, song: Song): Song {
        val obj = readAll(context).optJSONObject(song.id.toString()) ?: return song
        val title = obj.optString("title", "").ifBlank { song.title }
        val artist = obj.optString("artist", "").ifBlank { song.artist }
        return song.copy(title = title, artist = artist)
    }

    fun getAllOverrides(context: Context): Map<Long, Pair<String, String>> {
        val all = readAll(context)
        val map = HashMap<Long, Pair<String, String>>(all.length())
        all.keys().asSequence().forEach { key ->
            key.toLongOrNull()?.let { id ->
                val obj = all.optJSONObject(key)
                if (obj != null) {
                    map[id] = obj.optString("title", "") to obj.optString("artist", "")
                }
            }
        }
        return map
    }

    private fun readAll(context: Context): JSONObject {
        val json = prefs(context).getString(KEY_OVERRIDES, null) ?: return JSONObject()
        return try { JSONObject(json) } catch (e: Exception) { JSONObject() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

