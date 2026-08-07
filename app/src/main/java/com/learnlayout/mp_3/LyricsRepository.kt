package com.learnlayout.mp_3

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.regex.Pattern

data class LyricsLine(
    val timeMs: Long,
    val text: String
)

data class LyricsResult(
    val plainLyrics: String?,
    val syncedLines: List<LyricsLine>?,
    val isInstrumental: Boolean = false
)

/**
 * Cliente para la API publica de LRCLIB (https://lrclib.net).
 * No requiere API key. Devuelve letra plana y, cuando existe, letra
 * sincronizada en formato LRC ("[mm:ss.xx] texto").
 */
object LyricsRepository {

    private const val TAG = "LyricsRepository"
    private const val BASE_URL = "https://lrclib.net/api"

    // LRCLIB exige identificar al cliente en cada request; sin este header
    // el servidor corta la conexion o responde vacio y todo se ve como
    // "no encontrado" sin importar la cancion.
    private const val USER_AGENT = "MP_3-AndroidApp/1.0 (github.com/learnlayout/mp_3)"

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lrcLinePattern = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})]\\s*(.*)")

    interface LyricsCallback {
        fun onSuccess(result: LyricsResult)
        fun onError(message: String)
    }

    fun fetch(title: String, artist: String, durationSeconds: Long, callback: LyricsCallback) {
        val (cleanTitle, cleanArtist) = sanitizeTitleArtist(title, artist)
        Log.d(TAG, "Buscando letra -> title=\"$cleanTitle\" artist=\"$cleanArtist\" (original title=\"$title\" artist=\"$artist\")")

        val getUrl = buildGetUrl(cleanTitle, cleanArtist, durationSeconds)
        val request = Request.Builder()
            .url(getUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        Log.d(TAG, "GET $getUrl")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "GET falló, se intenta con /search", e)
                fetchViaSearch(cleanTitle, cleanArtist, callback)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    Log.d(TAG, "GET respondió código ${it.code}")
                    if (!it.isSuccessful) {
                        fetchViaSearch(cleanTitle, cleanArtist, callback)
                        return
                    }
                    val body = it.body?.string()
                    if (body.isNullOrBlank()) {
                        fetchViaSearch(cleanTitle, cleanArtist, callback)
                        return
                    }
                    try {
                        val result = parseSingleResult(JSONObject(body))
                        mainHandler.post { callback.onSuccess(result) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parseando respuesta de /get", e)
                        fetchViaSearch(cleanTitle, cleanArtist, callback)
                    }
                }
            }
        })
    }

    private fun fetchViaSearch(title: String, artist: String, callback: LyricsCallback) {
        val searchUrl = "$BASE_URL/search?track_name=${encode(title)}&artist_name=${encode(artist)}"
        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        Log.d(TAG, "SEARCH $searchUrl")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "SEARCH falló", e)
                mainHandler.post { callback.onError("Sin conexión o LRCLIB no respondió") }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    Log.d(TAG, "SEARCH respondió código ${it.code}")
                    if (!it.isSuccessful) {
                        mainHandler.post { callback.onError("No se encontró letra para esta canción") }
                        return
                    }
                    val body = it.body?.string()
                    try {
                        val array = JSONArray(body ?: "[]")
                        if (array.length() == 0) {
                            mainHandler.post { callback.onError("No se encontró letra para esta canción") }
                            return
                        }
                        val result = parseSingleResult(array.getJSONObject(0))
                        mainHandler.post { callback.onSuccess(result) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parseando respuesta de /search", e)
                        mainHandler.post { callback.onError("Error leyendo la respuesta de LRCLIB") }
                    }
                }
            }
        })
    }

    private fun parseSingleResult(json: JSONObject): LyricsResult {
        val instrumental = json.optBoolean("instrumental", false)
        val plain = json.optString("plainLyrics", "").ifBlank { null }
        val syncedRaw = json.optString("syncedLyrics", "").ifBlank { null }
        val synced = syncedRaw?.let { parseLrc(it) }
        return LyricsResult(plainLyrics = plain, syncedLines = synced, isInstrumental = instrumental)
    }

    private fun parseLrc(raw: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        raw.lines().forEach { line ->
            val matcher = lrcLinePattern.matcher(line.trim())
            if (matcher.matches()) {
                val minutes = matcher.group(1)!!.toLong()
                val seconds = matcher.group(2)!!.toLong()
                val fraction = matcher.group(3)!!
                val millis = if (fraction.length == 2) fraction.toLong() * 10 else fraction.toLong()
                val text = matcher.group(4) ?: ""
                val timeMs = (minutes * 60_000L) + (seconds * 1000L) + millis
                lines.add(LyricsLine(timeMs, text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun buildGetUrl(title: String, artist: String, durationSeconds: Long): String {
        return "$BASE_URL/get?track_name=${encode(title)}&artist_name=${encode(artist)}&duration=$durationSeconds"
    }

    /**
     * Limpia title/artist que vienen de nombres de archivo sin tags ID3, por ejemplo:
     * title="AC_DC_-_Shoot_To_Thrill", artist="<unknown>"
     * Convierte "_" en espacios y, si no hay artista real, intenta separar el
     * patron "Artista - Cancion" que suele venir junto en el nombre del archivo.
     */
    private fun sanitizeTitleArtist(rawTitle: String, rawArtist: String): Pair<String, String> {
        var title = normalizeSpacing(rawTitle)
        var artist = normalizeSpacing(rawArtist)

        val noArtist = artist.isBlank() ||
                artist.equals("<unknown>", ignoreCase = true) ||
                artist.equals("unknown", ignoreCase = true) ||
                artist.equals("unknown artist", ignoreCase = true)

        if (noArtist) {
            val parts = title.split(Regex("\\s*-\\s*"), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                artist = parts[0].trim()
                title = parts[1].trim()
            }
        }

        return title to artist
    }

    private fun normalizeSpacing(value: String): String {
        return value
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
