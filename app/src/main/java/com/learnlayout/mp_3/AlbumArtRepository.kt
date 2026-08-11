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
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Busca caratulas de album por titulo/artista, primero en iTunes y si no
 * hay resultado en Deezer. Cachea en memoria (LruCache) y en disco
 * (cacheDir/album_art_cache), asi que la mayoria de las veces no vuelve
 * a pegarle a la red.
 *
 * No toca el equalizador ni ninguna otra pantalla: solo resuelve un
 * Bitmap para un Song dado.
 */
object AlbumArtRepository {

    private const val CACHE_DIR_NAME = "album_art_cache"
    private const val MEMORY_CACHE_SIZE = 60

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val memoryCache = object : LruCache<Long, Bitmap>(MEMORY_CACHE_SIZE) {}

    // Evita relanzar la misma busqueda de red si ya hay una en curso
    // para la misma cancion (pasa seguido con RecyclerView haciendo scroll).
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Long>())

    interface Callback {
        fun onCoverReady(bitmap: Bitmap)
    }

    /**
     * Pide la caratula de [song]. Llama a [callback] en el hilo principal
     * SOLO si la encuentra (memoria, disco o red). Si no hay caratula
     * disponible, no llama a [callback]: quien la pidio debe dejar el
     * placeholder que ya tenia puesto.
     */
    fun loadCover(context: Context, song: Song, callback: Callback) {
        memoryCache.get(song.id)?.let {
            callback.onCoverReady(it)
            return
        }

        if (!inFlight.add(song.id)) return

        val appContext = context.applicationContext
        executor.execute {
            try {
                val cacheKey = cacheKeyFor(song)
                val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")

                val fromDisk = if (diskFile.exists()) {
                    BitmapFactory.decodeFile(diskFile.absolutePath)
                } else null

                val bitmap = fromDisk ?: fetchFromNetwork(song)?.also { bmp ->
                    saveToDisk(diskFile, bmp)
                }

                if (bitmap != null) {
                    memoryCache.put(song.id, bitmap)
                    mainHandler.post { callback.onCoverReady(bitmap) }
                }
            } finally {
                inFlight.remove(song.id)
            }
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
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
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
}