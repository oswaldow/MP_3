package com.learnlayout.mp_3

import android.content.Context
import org.json.JSONObject

object PlayCountRepository {

    private const val PREFS_NAME = "play_count_prefs"
    private const val KEY_COUNTS = "play_counts_json"

    fun getPlayCount(context: Context, songId: Long): Int {
        return getJson(context).optInt(songId.toString(), 0)
    }

    fun incrementPlayCount(context: Context, songId: Long) {
        val json = getJson(context)
        val current = json.optInt(songId.toString(), 0)
        json.put(songId.toString(), current + 1)
        saveJson(context, json)
    }

    private fun getJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_COUNTS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveJson(context: Context, json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COUNTS, json.toString()).apply()
    }
}