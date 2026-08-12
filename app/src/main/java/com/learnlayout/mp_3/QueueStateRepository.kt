package com.learnlayout.mp_3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistencia de la cola REAL del reproductor.
 *
 * Se guardan solamente IDs de canciones, no objetos Song completos. De esta
 * forma la cola puede sobrevivir a que Android mate el proceso y se reconstruye
 * contra la biblioteca actual al volver a abrir la app.
 */
object QueueStateRepository {

    private const val PREFS_NAME = "mp3_queue_state"
    private const val KEY_STATE = "queue_state_json"

    data class QueueState(
        val queueIds: List<Long>,
        val originalQueueIds: List<Long>,
        val currentIndex: Int,
        val currentSongId: Long,
        val playbackMode: MusicService.PlaybackMode
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        queueIds: List<Long>,
        originalQueueIds: List<Long>,
        currentIndex: Int,
        currentSongId: Long?,
        playbackMode: MusicService.PlaybackMode
    ) {
        prefs(context).edit()
            .putString(
                KEY_STATE,
                buildJson(
                    queueIds,
                    originalQueueIds,
                    currentIndex,
                    currentSongId ?: -1L,
                    playbackMode
                ).toString()
            )
            .apply()
    }

    /** Version bloqueante para onTaskRemoved()/onDestroy(). */
    fun saveBlocking(
        context: Context,
        queueIds: List<Long>,
        originalQueueIds: List<Long>,
        currentIndex: Int,
        currentSongId: Long?,
        playbackMode: MusicService.PlaybackMode
    ) {
        prefs(context).edit()
            .putString(
                KEY_STATE,
                buildJson(
                    queueIds,
                    originalQueueIds,
                    currentIndex,
                    currentSongId ?: -1L,
                    playbackMode
                ).toString()
            )
            .commit()
    }

    fun get(context: Context): QueueState? {
        val raw = prefs(context).getString(KEY_STATE, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val queueIds = readIds(json.optJSONArray("queueIds"))
            if (queueIds.isEmpty()) return null

            val originalIds = readIds(json.optJSONArray("originalQueueIds"))
            val mode = try {
                MusicService.PlaybackMode.valueOf(
                    json.optString("playbackMode", MusicService.PlaybackMode.NORMAL.name)
                )
            } catch (_: IllegalArgumentException) {
                MusicService.PlaybackMode.NORMAL
            }

            QueueState(
                queueIds = queueIds,
                originalQueueIds = originalIds,
                currentIndex = json.optInt("currentIndex", 0),
                currentSongId = json.optLong("currentSongId", -1L),
                playbackMode = mode
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_STATE).apply()
    }

    private fun buildJson(
        queueIds: List<Long>,
        originalQueueIds: List<Long>,
        currentIndex: Int,
        currentSongId: Long,
        playbackMode: MusicService.PlaybackMode
    ): JSONObject {
        return JSONObject().apply {
            put("queueIds", JSONArray().apply {
                queueIds.forEach { put(it) }
            })
            put("originalQueueIds", JSONArray().apply {
                originalQueueIds.forEach { put(it) }
            })
            put("currentIndex", currentIndex)
            put("currentSongId", currentSongId)
            put("playbackMode", playbackMode.name)
            put("version", 1)
        }
    }

    private fun readIds(array: JSONArray?): List<Long> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                add(array.optLong(i, Long.MIN_VALUE))
            }
        }.filter { it != Long.MIN_VALUE }
    }
}