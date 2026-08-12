package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AlbumArtRepository {

    private const val CACHE_DIR_NAME = "album_art_cache"
    private const val MEMORY_CACHE_SIZE = 100
    private const val CANDIDATES_LIMIT_PER_SOURCE = 6

    // Tamano maximo (en px, en el lado mas largo) al que se decodifican las
    // caratulas. Cubre tanto el item chico de la lista (48dp) como el
    // reproductor expandido (que ocupa casi todo el ancho de pantalla), asi
    // que no se ve borroso ahi, pero evita decodificar bitmaps gigantes
    // (600x600+ de red, o mas grandes todavia de Deezer) para nada.
    private const val TARGET_MAX_DIMENSION_PX = 480

    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val memoryCache = object : LruCache<Long, Bitmap>(MEMORY_CACHE_SIZE) {}

    // Evita relanzar la misma busqueda de red si ya hay una en curso
    // para la misma cancion (pasa seguido con RecyclerView haciendo scroll).
    /**
     * Canciones que estan cargando su caratula. Una misma caratula puede ser
     * solicitada al mismo tiempo por varias vistas (cola + reproductor + lista).
     * En vez de ignorar las solicitudes posteriores, las dejamos esperando y
     * entregamos el bitmap a todas cuando termina la carga.
     */
    private val inFlight = mutableSetOf<Long>()
    private val pendingCallbacks = mutableMapOf<Long, MutableList<PendingRequest>>()

    private data class PendingRequest(
        val callback: Callback,
        val isStillNeeded: () -> Boolean
    )

    interface Callback {
        fun onCoverReady(bitmap: Bitmap)
    }

    /** Una opcion de caratula para elegir manualmente (ver [searchCandidates]). */
    data class AlbumArtCandidate(
        val bitmap: Bitmap,
        val sourceLabel: String
    )

    interface CandidatesCallback {
        fun onCandidatesReady(candidates: List<AlbumArtCandidate>)
    }

    /**
     * Devuelve la caratula si ya esta en cache de memoria, sin disparar
     * ninguna carga de disco/red. Sirve para pintarla directo (sin pasar
     * por el placeholder ni el fade) cuando ya se sabe que esta disponible
     * al instante: evita el parpadeo de placeholder->caratula al reabrir
     * la app, scrollear la lista, o volver a una cancion ya vista.
     */
    fun getCachedCover(song: Song): Bitmap? = memoryCache.get(song.id)

    /**
     * Pide la caratula de [song]. Llama a [callback] en el hilo principal
     * SOLO si la encuentra (memoria, disco o red) y sigue siendo necesaria.
     * Si no hay caratula disponible, no llama a [callback]: quien la pidio
     * debe dejar el placeholder que ya tenia puesto.
     *
     * [isStillNeeded] es opcional y sirve para que quien pide la caratula
     * (tipicamente un RecyclerView.Adapter) avise si la vista que la pidio
     * ya se reciclo para otra cancion. Se chequea antes de tocar disco/red
     * y antes de entregar el resultado: si durante un scroll rapido la
     * vista ya no necesita esta caratula, la tarea se descarta enseguida en
     * vez de competir por un hilo del pool con las que si son visibles.
     */
    fun loadCover(
        context: Context,
        song: Song,
        callback: Callback,
        isStillNeeded: () -> Boolean = { true }
    ) {
        memoryCache.get(song.id)?.let {
            if (isStillNeeded()) callback.onCoverReady(it)
            return
        }

        val shouldStartLoad = synchronized(inFlight) {
            pendingCallbacks
                .getOrPut(song.id) { mutableListOf() }
                .add(PendingRequest(callback, isStillNeeded))

            inFlight.add(song.id)
        }

        if (!shouldStartLoad) return

        val appContext = context.applicationContext
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                val stillNeeded = synchronized(inFlight) {
                    pendingCallbacks[song.id]?.any { it.isStillNeeded() } == true
                }
                if (!stillNeeded) return@execute

                val cacheKey = cacheKeyFor(song)
                val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")

                val fromDisk = if (diskFile.exists()) {
                    decodeSampledBitmapFromFile(diskFile)
                } else null

                bitmap = fromDisk ?: run {
                    val needsNetwork = synchronized(inFlight) {
                        pendingCallbacks[song.id]?.any { it.isStillNeeded() } == true
                    }
                    if (needsNetwork) {
                        fetchFromNetwork(song)?.also { bmp -> saveToDisk(diskFile, bmp) }
                    } else null
                }

                if (bitmap != null) {
                    memoryCache.put(song.id, bitmap)
                }
            } finally {
                finishInFlight(song.id, bitmap)
            }
        }
    }

    /**
     * Version de [loadCover] que NUNCA pega a la red: solo revisa memoria
     * y disco. La usa el reproductor (mini player y panel expandido) para
     * que escuchar musica no dispare busquedas de caratula por wifi/datos
     * cada vez que cambia la cancion. La busqueda en red solo se dispara
     * ahora al mantener presionada la caratula (ver AlbumArtPickerDialog)
     * o desde la descarga masiva en Configuracion (ver [prefetchCover]),
     * que es la que deja la caratula ya guardada en disco para que esta
     * funcion la encuentre despues sin tocar la red.
     */
    fun loadCoverCacheOnly(
        context: Context,
        song: Song,
        callback: Callback,
        isStillNeeded: () -> Boolean = { true }
    ) {
        memoryCache.get(song.id)?.let {
            if (isStillNeeded()) callback.onCoverReady(it)
            return
        }

        val shouldStartLoad = synchronized(inFlight) {
            pendingCallbacks
                .getOrPut(song.id) { mutableListOf() }
                .add(PendingRequest(callback, isStillNeeded))

            inFlight.add(song.id)
        }

        // Otra parte de la app ya esta cargando esta misma caratula.
        // Nos quedamos registrados como listener para recibir el bitmap cuando
        // termine. Esto evita que el reproductor se quede con placeholder
        // mientras la cola ya esta cargando la misma portada.
        if (!shouldStartLoad) return

        val appContext = context.applicationContext
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                val stillNeeded = synchronized(inFlight) {
                    pendingCallbacks[song.id]?.any { it.isStillNeeded() } == true
                }
                if (!stillNeeded) return@execute

                val cacheKey = cacheKeyFor(song)
                val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")
                if (!diskFile.exists()) return@execute

                bitmap = decodeSampledBitmapFromFile(diskFile)
                if (bitmap != null) {
                    memoryCache.put(song.id, bitmap)
                }
            } finally {
                finishInFlight(song.id, bitmap)
            }
        }
    }

    /**
     * Entrega el resultado a todas las vistas que estaban esperando la misma
     * caratula. Se ejecuta una sola vez por carga y siempre publica callbacks
     * en el hilo principal.
     */
    private fun finishInFlight(songId: Long, bitmap: Bitmap?) {
        val callbacks = synchronized(inFlight) {
            inFlight.remove(songId)
            pendingCallbacks.remove(songId)?.toList().orEmpty()
        }

        if (bitmap == null || callbacks.isEmpty()) return

        callbacks.forEach { request ->
            if (!request.isStillNeeded()) return@forEach
            mainHandler.post {
                if (request.isStillNeeded()) {
                    request.callback.onCoverReady(bitmap)
                }
            }
        }
    }

    /**
     * Descarga (si hace falta) y guarda en disco la caratula de [song] sin
     * necesitar pintarla en ninguna vista. A diferencia de [loadCover],
     * SIEMPRE llama a [onComplete] al terminar -haya encontrado caratula o
     * no- para que quien dispara descargas en lote (ver
     * SettingsActivity.downloadAllLyrics) pueda avanzar a la siguiente
     * cancion sin quedarse esperando. [onComplete] recibe true si la
     * caratula quedo disponible en disco (ya estaba o se acaba de
     * descargar) y false si no se encontro en ninguna fuente. No decodifica
     * el bitmap completo a memoria (solo lo necesario para comprimirlo a
     * disco), para no acumular cientos de bitmaps en RAM durante una
     * descarga masiva.
     */
    fun prefetchCover(context: Context, song: Song, onComplete: (found: Boolean) -> Unit) {
        if (memoryCache.get(song.id) != null) {
            onComplete(true)
            return
        }

        val appContext = context.applicationContext
        executor.execute {
            var found = false
            try {
                val cacheKey = cacheKeyFor(song)
                val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")
                found = diskFile.exists()
                if (!found) {
                    val bitmap = fetchFromNetwork(song)
                    if (bitmap != null) {
                        saveToDisk(diskFile, bitmap)
                        found = true
                    }
                }
            } catch (e: Exception) {
                // found se queda en su ultimo valor conocido (false si aun
                // no se habia resuelto nada).
            } finally {
                val result = found
                mainHandler.post { onComplete(result) }
            }
        }
    }

    /**
     * Busca varias posibles caratulas para [song] (iTunes + Deezer, hasta
     * [CANDIDATES_LIMIT_PER_SOURCE] de cada una) para que el usuario elija
     * manualmente cual es la correcta cuando la automatica no coincide.
     * No toca la cache: solo devuelve opciones para previsualizar.
     */
    fun searchCandidates(song: Song, callback: CandidatesCallback) {
        val query = "${song.artist} ${song.title}".trim()
        if (query.isBlank()) {
            callback.onCandidatesReady(emptyList())
            return
        }

        executor.execute {
            val candidates = mutableListOf<AlbumArtCandidate>()
            candidates += fetchItunesCandidates(query)
            candidates += fetchDeezerCandidates(query)
            mainHandler.post { callback.onCandidatesReady(candidates) }
        }
    }

    /**
     * Guarda [bitmap] como la caratula elegida a mano para [song]: la
     * escribe en el mismo lugar (memoria + disco) que usa el flujo
     * automatico, asi que a partir de ahora [loadCover] la devuelve como
     * si fuera el resultado normal de la busqueda.
     */
    fun applyOverride(context: Context, song: Song, bitmap: Bitmap, callback: Callback) {
        val appContext = context.applicationContext
        executor.execute {
            val cacheKey = cacheKeyFor(song)
            val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")
            saveToDisk(diskFile, bitmap)
            memoryCache.put(song.id, bitmap)
            mainHandler.post { callback.onCoverReady(bitmap) }
        }
    }

    private fun fetchItunesCandidates(query: String): List<AlbumArtCandidate> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=$CANDIDATES_LIMIT_PER_SOURCE"
            val json = httpGetJson(url) ?: return emptyList()
            val results = json.optJSONArray("results") ?: return emptyList()

            val out = mutableListOf<AlbumArtCandidate>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val artworkUrl = item.optString("artworkUrl100", "")
                if (artworkUrl.isBlank()) continue

                val highRes = artworkUrl.replace("100x100bb", "300x300bb")
                val bitmap = downloadBitmap(highRes) ?: continue

                val trackName = item.optString("trackName", "")
                val artistName = item.optString("artistName", "")
                val label = listOf(trackName, artistName).filter { it.isNotBlank() }.joinToString(" - ")
                out += AlbumArtCandidate(bitmap, label.ifBlank { "iTunes" })
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchDeezerCandidates(query: String): List<AlbumArtCandidate> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.deezer.com/search?q=$encoded&limit=$CANDIDATES_LIMIT_PER_SOURCE"
            val json = httpGetJson(url) ?: return emptyList()
            val data = json.optJSONArray("data") ?: return emptyList()

            val out = mutableListOf<AlbumArtCandidate>()
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val album = item.optJSONObject("album") ?: continue
                val coverUrl = album.optString("cover_medium", "").ifBlank {
                    album.optString("cover_big", "")
                }
                if (coverUrl.isBlank()) continue

                val bitmap = downloadBitmap(coverUrl) ?: continue

                val trackTitle = item.optString("title", "")
                val artistName = item.optJSONObject("artist")?.optString("name", "") ?: ""
                val label = listOf(trackTitle, artistName).filter { it.isNotBlank() }.joinToString(" - ")
                out += AlbumArtCandidate(bitmap, label.ifBlank { "Deezer" })
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchFromNetwork(song: Song): Bitmap? {
        val query = "${song.artist} ${song.title}".trim()
        if (query.isBlank()) return null

        return fetchFromItunes(query) ?: fetchFromDeezer(query)
    }

    private fun fetchFromItunes(query: String): Bitmap? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=1"
            val json = httpGetJson(url) ?: return null
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val artworkUrl = results.getJSONObject(0).optString("artworkUrl100", "")
            if (artworkUrl.isBlank()) return null

            // iTunes devuelve 100x100 por defecto; se pide una version mas grande.
            val highRes = artworkUrl.replace("100x100bb", "600x600bb")
            downloadBitmap(highRes)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFromDeezer(query: String): Bitmap? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.deezer.com/search?q=$encoded&limit=1"
            val json = httpGetJson(url) ?: return null
            val data = json.optJSONArray("data") ?: return null
            if (data.length() == 0) return null

            val album = data.getJSONObject(0).optJSONObject("album") ?: return null
            val coverUrl = album.optString("cover_xl", "").ifBlank {
                album.optString("cover_big", "")
            }
            if (coverUrl.isBlank()) return null

            downloadBitmap(coverUrl)
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGetJson(urlString: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun downloadBitmap(urlString: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            decodeSampledBitmapFromBytes(bytes)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun saveToDisk(file: File, bitmap: Bitmap) {
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            // Si falla el guardado no pasa nada, se reintenta la proxima vez.
        }
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR_NAME)

    private fun cacheKeyFor(song: Song): String {
        val raw = "${song.artist}|${song.title}".lowercase()
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---------- Decode con downsampling ----------
    // Decodificar un bitmap a su tamano original solo para mostrarlo en un
    // ImageView chico (o incluso en el panel expandido) desperdicia tiempo
    // de CPU y memoria. Estas funciones calculan un inSampleSize con el
    // truco estandar de Android (leer primero solo las dimensiones con
    // inJustDecodeBounds) para decodificar directamente a un tamano cercano
    // al que realmente se va a usar.

    private fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
        var inSampleSize = 1
        if (width > targetSize || height > targetSize) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while (halfWidth / inSampleSize >= targetSize && halfHeight / inSampleSize >= targetSize) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun decodeSampledBitmapFromFile(file: File): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                boundsOptions.outWidth,
                boundsOptions.outHeight,
                TARGET_MAX_DIMENSION_PX
            )
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun decodeSampledBitmapFromBytes(bytes: ByteArray): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                boundsOptions.outWidth,
                boundsOptions.outHeight,
                TARGET_MAX_DIMENSION_PX
            )
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
