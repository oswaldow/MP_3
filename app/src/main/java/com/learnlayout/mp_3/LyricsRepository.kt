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
 * Una opcion de letra entre varias que devuelve LRCLIB para la misma
 * busqueda (distintas versiones/duraciones/idiomas). Ver [LyricsRepository
 * .searchCandidates], usado por el selector que se abre al mantener
 * presionada la caratula del reproductor.
 */
data class LyricsCandidate(
    val label: String,
    val result: LyricsResult,
    val durationSeconds: Long? = null
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

    // Etiqueta opcional del formato LRC: "[offset:+/-milisegundos]". Ajusta
    // TODOS los timestamps del archivo. La convierten pocas letras de
    // LRCLIB, pero cuando aparece y no se aplica, un offset positivo hace
    // que casi todas las lineas "ya hayan pasado" desde el segundo 0, y
    // por eso la letra activa salta directo al final apenas empieza la
    // cancion y se queda ahi (ver parseLrc).
    private val lrcOffsetPattern = Pattern.compile("\\[offset:\\s*(-?\\d+)]", Pattern.CASE_INSENSITIVE)

    interface LyricsCallback {
        fun onSuccess(result: LyricsResult)
        fun onError(message: String)
    }

    interface LyricsCandidatesCallback {
        fun onCandidatesReady(candidates: List<LyricsCandidate>)
    }

    /**
     * Descarga automatica de letra para [title]/[artist]/[durationSeconds]
     * (la duracion real, en segundos, del archivo de audio ya descargado).
     *
     * FIX: antes esta funcion probaba primero /get (que en LRCLIB devuelve
     * UN solo resultado elegido por el servidor) y solo si fallaba caia a
     * /search tomando a ciegas el primer resultado del array, sin mirar si
     * ese resultado tenia letra SINCRONIZADA ni que tan cerca estaba su
     * duracion de la real. Eso podia traer una letra plana (sin sincronizar)
     * cuando SI existia una version sincronizada para la misma cancion, o
     * una version de duracion muy distinta (ej. version de radio vs. version
     * de album) en vez de la que mejor coincide con el archivo real.
     *
     * Ahora se consulta siempre /search (que trae varias versiones
     * candidatas para el mismo title/artist) y se elige la mejor segun dos
     * filtros, en este orden de prioridad (ver [pickBestResult]):
     *   1) preferencia por letra sincronizada sobre letra plana.
     *   2) entre las que empatan en el filtro anterior, la de duracion mas
     *      cercana a [durationSeconds].
     * Solo si /search no da ningun resultado usable, se cae a /get como
     * ultimo recurso (por si el catalogo de LRCLIB indexa esa cancion de
     * forma distinta para busqueda directa que para busqueda por texto).
     */
    fun fetch(title: String, artist: String, durationSeconds: Long, callback: LyricsCallback) {
        val (cleanTitle, cleanArtist) = sanitizeTitleArtist(title, artist)
        fetchBestViaSearch(cleanTitle, cleanArtist, durationSeconds, callback)
    }

    /**
     * Busca TODAS las coincidencias que LRCLIB tenga para title/artist (via
     * /search) y elige la mejor aplicando los filtros de [pickBestResult]:
     * preferencia por sincronizada y, entre las que empatan, la de duracion
     * mas cercana a [durationSeconds]. Si no encuentra nada usable, cae a
     * /get como ultimo recurso.
     */
    private fun fetchBestViaSearch(
        title: String,
        artist: String,
        durationSeconds: Long,
        callback: LyricsCallback
    ) {
        val searchUrl = "$BASE_URL/search?track_name=${encode(title)}&artist_name=${encode(artist)}"
        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "SEARCH falló, se intenta con /get", e)
                fetchViaGet(title, artist, durationSeconds, callback)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        fetchViaGet(title, artist, durationSeconds, callback)
                        return
                    }
                    val body = it.body?.string()
                    try {
                        val array = JSONArray(body ?: "[]")
                        val best = pickBestResult(array, durationSeconds)
                        if (best != null) {
                            mainHandler.post { callback.onSuccess(best) }
                        } else {
                            Log.d(TAG, "SEARCH sin candidatos usables, se intenta con /get")
                            fetchViaGet(title, artist, durationSeconds, callback)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parseando respuesta de /search", e)
                        fetchViaGet(title, artist, durationSeconds, callback)
                    }
                }
            }
        })
    }

    /**
     * Ultimo recurso: pide directamente /get (title+artist+duration), que
     * en LRCLIB devuelve un unico resultado elegido por el propio servidor.
     * Se usa solo cuando /search no encontro nada, asi que aqui no aplican
     * los filtros de sincronizacion/duracion (no hay entre que elegir).
     */
    private fun fetchViaGet(
        title: String,
        artist: String,
        durationSeconds: Long,
        callback: LyricsCallback
    ) {
        val getUrl = buildGetUrl(title, artist, durationSeconds)
        val request = Request.Builder()
            .url(getUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "GET también falló", e)
                mainHandler.post { callback.onError("Sin conexión o LRCLIB no respondió") }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        mainHandler.post { callback.onError("No se encontró letra para esta canción") }
                        return
                    }
                    val body = it.body?.string()
                    if (body.isNullOrBlank()) {
                        mainHandler.post { callback.onError("No se encontró letra para esta canción") }
                        return
                    }
                    try {
                        val result = parseSingleResult(JSONObject(body))
                        mainHandler.post { callback.onSuccess(result) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parseando respuesta de /get", e)
                        mainHandler.post { callback.onError("Error leyendo la respuesta de LRCLIB") }
                    }
                }
            }
        })
    }

    /**
     * Elige, entre todos los resultados de [array] (respuesta cruda de
     * /search), el que mejor cumple los dos filtros pedidos, en este orden
     * de prioridad:
     *
     * 1) SINCRONIZACION: un resultado con letra sincronizada (syncedLyrics
     *    no vacio) siempre gana sobre uno que solo tenga letra plana, sin
     *    importar que tan lejos este su duracion de [targetDurationSeconds].
     *
     * 2) DURACION: entre los resultados que empatan en el filtro anterior
     *    (todos sincronizados, o todos sin sincronizar si ninguno lo esta),
     *    se elige el que tenga la duracion mas cercana a
     *    [targetDurationSeconds]. Los resultados sin duracion informada se
     *    tratan como "lo mas lejos posible" (van al final), para no
     *    preferirlos por accidente sobre uno que si informa una duracion
     *    cercana, pero sin descartarlos: si son la unica opcion sincronizada
     *    disponible, igual se eligen.
     *
     * Descarta candidatos sin contenido util (marcados instrumental sin
     * texto, o sin letra plana ni sincronizada). Devuelve null si ningun
     * resultado del array sirve.
     */
    private fun pickBestResult(array: JSONArray, targetDurationSeconds: Long): LyricsResult? {
        data class ScoredResult(
            val result: LyricsResult,
            val isSynced: Boolean,
            val durationDistance: Long
        )

        val scored = mutableListOf<ScoredResult>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val result = parseSingleResult(obj)

            val hasContent = !result.isInstrumental &&
                    (!result.syncedLines.isNullOrEmpty() || !result.plainLyrics.isNullOrBlank())
            if (!hasContent) continue

            val candidateDuration = obj.optLong("duration", -1L).takeIf { it > 0L }
            val distance = if (candidateDuration != null) {
                kotlin.math.abs(candidateDuration - targetDurationSeconds)
            } else {
                Long.MAX_VALUE
            }

            scored += ScoredResult(
                result = result,
                isSynced = !result.syncedLines.isNullOrEmpty(),
                durationDistance = distance
            )
        }

        if (scored.isEmpty()) return null

        return scored
            .sortedWith(
                compareByDescending<ScoredResult> { it.isSynced }
                    .thenBy { it.durationDistance }
            )
            .first()
            .result
    }

    /**
     * Busca TODAS las coincidencias que LRCLIB tenga para title/artist (via
     * /search, no /get) para que el usuario elija manualmente cual letra
     * corresponde a la version que tiene. Se usa desde el selector que se
     * abre al mantener presionada la caratula del reproductor, junto con
     * [AlbumArtRepository.searchCandidates]. Descarta candidatos sin
     * contenido util (instrumental sin texto, o sin letra plana/sincronizada).
     */
    fun searchCandidates(title: String, artist: String, callback: LyricsCandidatesCallback) {
        val (cleanTitle, cleanArtist) = sanitizeTitleArtist(title, artist)
        val searchUrl = "$BASE_URL/search?track_name=${encode(cleanTitle)}&artist_name=${encode(cleanArtist)}"
        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "SEARCH (candidates) falló", e)
                mainHandler.post { callback.onCandidatesReady(emptyList()) }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        mainHandler.post { callback.onCandidatesReady(emptyList()) }
                        return
                    }
                    val body = it.body?.string()
                    try {
                        val array = JSONArray(body ?: "[]")
                        val out = mutableListOf<LyricsCandidate>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            val result = parseSingleResult(obj)
                            val hasContent = !result.isInstrumental &&
                                    (!result.syncedLines.isNullOrEmpty() || !result.plainLyrics.isNullOrBlank())
                            if (!hasContent) continue

                            out += LyricsCandidate(
                                label = buildCandidateLabel(obj, result, cleanTitle, cleanArtist),
                                result = result,
                                durationSeconds = obj.optLong("duration", -1L).takeIf { it > 0L }
                            )
                        }
                        mainHandler.post { callback.onCandidatesReady(out) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parseando respuesta de /search (candidates)", e)
                        mainHandler.post { callback.onCandidatesReady(emptyList()) }
                    }
                }
            }
        })
    }

    private fun buildCandidateLabel(
        obj: JSONObject,
        result: LyricsResult,
        fallbackTitle: String,
        fallbackArtist: String
    ): String {
        val trackName = obj.optString("trackName", fallbackTitle).ifBlank { fallbackTitle }
        val artistName = obj.optString("artistName", fallbackArtist).ifBlank { fallbackArtist }
        val syncLabel = if (!result.syncedLines.isNullOrEmpty()) "Sincronizada" else "Sin sincronizar"

        return "$trackName - $artistName - $syncLabel"
    }

    private fun parseSingleResult(json: JSONObject): LyricsResult {
        val instrumental = json.optBoolean("instrumental", false)
        val plain = optNullableString(json, "plainLyrics")
        val syncedRaw = optNullableString(json, "syncedLyrics")

        val synced = syncedRaw?.let { parseLrc(it) }
        return LyricsResult(plainLyrics = plain, syncedLines = synced, isInstrumental = instrumental)
    }

    /**
     * org.json.JSONObject.optString() tiene una trampa: si la clave EXISTE
     * pero su valor es JSON null, no devuelve el default que le pasas, sino
     * el string literal "null" (4 caracteres). Eso hacia que una cancion
     * SIN letra sincronizada en LRCLIB ("syncedLyrics": null) se tratara
     * como si su letra sincronizada fuera el texto "null", provocando 0
     * lineas parseadas y una caida al modo no-sincronizado (todas las
     * lineas con timeMs = -1), lo que hacia que el resaltado saltara
     * directo al final de la letra desde el segundo 0.
     */
    private fun optNullableString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key, "").ifBlank { null }
    }

    /**
     * Interpreta el texto plano guardado en el campo LYRICS embebido de un
     * archivo de audio (ver EmbeddedMetadataReader). Muchos editores de
     * tags (incluida esta misma app, ver SongFileTagWriter) guardan ahi la
     * letra ya en formato LRC ("[mm:ss.xx] texto"), en cuyo caso se
     * reconoce como sincronizada igual que si viniera de LRCLIB. Si no
     * tiene ese formato, se trata como letra plana sin sincronizar.
     */
    fun parseEmbeddedText(raw: String): LyricsResult {
        val synced = parseLrc(raw)
        return if (synced.isNotEmpty()) {
            LyricsResult(plainLyrics = null, syncedLines = synced, isInstrumental = false)
        } else {
            LyricsResult(plainLyrics = raw, syncedLines = null, isInstrumental = false)
        }
    }

    private fun parseLrc(raw: String): List<LyricsLine> {
        val offsetMs = extractOffsetMs(raw)

        val lines = mutableListOf<LyricsLine>()
        raw.lines().forEach { line ->
            val matcher = lrcLinePattern.matcher(line.trim())
            if (matcher.matches()) {
                val minutes = matcher.group(1)!!.toLong()
                val seconds = matcher.group(2)!!.toLong()
                val fraction = matcher.group(3)!!
                val millis = if (fraction.length == 2) fraction.toLong() * 10 else fraction.toLong()
                val text = matcher.group(4) ?: ""
                val rawTimeMs = (minutes * 60_000L) + (seconds * 1000L) + millis
                // offset positivo = la letra debe adelantarse (verse antes),
                // por eso se RESTA al timestamp original; negativo la
                // atrasa. Es la convencion estandar del formato LRC.
                lines.add(LyricsLine(rawTimeMs - offsetMs, text))
            }
        }
        val sorted = lines.sortedBy { it.timeMs }

        return sorted
    }

    private fun extractOffsetMs(raw: String): Long {
        val matcher = lrcOffsetPattern.matcher(raw)
        return if (matcher.find()) {
            matcher.group(1)?.toLongOrNull() ?: 0L
        } else {
            0L
        }
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