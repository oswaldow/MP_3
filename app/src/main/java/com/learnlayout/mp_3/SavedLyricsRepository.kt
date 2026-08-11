package com.learnlayout.mp_3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Letras guardadas localmente para verlas sin conexion, indexadas por songId.
 */
object SavedLyricsRepository {

    private const val PREFS_NAME = "saved_lyrics_prefs"

    fun isSaved(context: Context, songId: Long): Boolean {
        return prefs(context).contains(keyFor(songId))
    }

    /**
     * Borra la letra guardada de [songId], si habia alguna. Se usa cuando
     * la cancion se elimina del dispositivo.
     */
    fun remove(context: Context, songId: Long) {
        if (prefs(context).contains(keyFor(songId))) {
            prefs(context).edit().remove(keyFor(songId)).apply()
        }
    }

    fun getSavedLyrics(context: Context, songId: Long): LyricsResult? {
        val json = prefs(context).getString(keyFor(songId), null) ?: return null
        return try {
            parse(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    fun toggleSaved(context: Context, songId: Long, result: LyricsResult): Boolean {
        return if (isSaved(context, songId)) {
            prefs(context).edit().remove(keyFor(songId)).apply()
            false
        } else {
            prefs(context).edit().putString(keyFor(songId), toJson(result).toString()).apply()
            true
        }
    }

    /**
     * Guarda (o sobreescribe) la letra de esta cancion sin importar si ya
     * habia una guardada antes. La usa el modo de sincronizacion manual:
     * cada vez que el usuario re-sincroniza a mano, esto reemplaza lo que
     * hubiera guardado previamente (LRCLIB o una sincronizacion anterior).
     */
    fun save(context: Context, songId: Long, result: LyricsResult) {
        prefs(context).edit().putString(keyFor(songId), toJson(result).toString()).apply()
    }

    private fun toJson(result: LyricsResult): JSONObject {
        val obj = JSONObject()
        obj.put("plainLyrics", result.plainLyrics ?: JSONObject.NULL)
        obj.put("isInstrumental", result.isInstrumental)
        val linesArray = JSONArray()
        result.syncedLines?.forEach { line ->
            val lineObj = JSONObject()
            lineObj.put("timeMs", line.timeMs)
            lineObj.put("text", line.text)
            linesArray.put(lineObj)
        }
        obj.put("syncedLines", linesArray)
        return obj
    }

    private fun parse(obj: JSONObject): LyricsResult {
        val plain = obj.optString("plainLyrics", "").ifBlank { null }
        val isInstrumental = obj.optBoolean("isInstrumental", false)
        val linesArray = obj.optJSONArray("syncedLines")
        val lines = if (linesArray != null && linesArray.length() > 0) {
            (0 until linesArray.length()).map {
                val lineObj = linesArray.getJSONObject(it)
                LyricsLine(lineObj.getLong("timeMs"), lineObj.getString("text"))
            }
        } else null
        return LyricsResult(plainLyrics = plain, syncedLines = lines, isInstrumental = isInstrumental)
    }

    private fun keyFor(songId: Long) = "song_$songId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
