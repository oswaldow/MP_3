package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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

    // Antes las caratulas se guardaban en context.cacheDir: esa carpeta la
    // puede borrar Android en cualquier momento (poco espacio, limpieza del
    // sistema, etc.) SIN que el usuario desinstale nada, lo que causaba que
    // a veces "desaparecieran" caratulas ya descargadas incluso sin
    // conexion. Ahora se guardan en context.filesDir, que solo se borra si
    // se desinstala la app o se limpia el almacenamiento a mano.
    private const val STORE_DIR_NAME = "album_art_store"
    // Carpeta vieja (dentro de cacheDir), solo para migrar una vez lo que
    // ya se hubiera descargado antes de este cambio.
    private const val LEGACY_CACHE_DIR_NAME = "album_art_cache"
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

    // ---------- Proteccion de elecciones manuales ----------
    // Cuando el usuario elige a mano una caratula (picker de iTunes/Deezer o
    // "Redescargar y sobreescribir" en Ajustes), esa eleccion NO debe volver
    // a pisarse sola por la logica "el archivo primero" de loadCoverCacheOnly.
    // Se persiste en SharedPreferences (no solo en memoria) porque tiene que
    // sobrevivir a que la app se cierre y se vuelva a abrir.
    private const val MANUAL_OVERRIDE_PREFS = "album_art_manual_overrides"
    private const val KEY_MANUAL_IDS = "song_ids"

    private fun manualOverridePrefs(context: Context) =
        context.getSharedPreferences(MANUAL_OVERRIDE_PREFS, Context.MODE_PRIVATE)

    private fun isManualOverride(context: Context, songId: Long): Boolean {
        return manualOverridePrefs(context).getStringSet(KEY_MANUAL_IDS, emptySet())
            ?.contains(songId.toString()) == true
    }

    private fun markManualOverride(context: Context, songId: Long) {
        val prefs = manualOverridePrefs(context)
        val current = prefs.getStringSet(KEY_MANUAL_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(songId.toString())
        prefs.edit().putStringSet(KEY_MANUAL_IDS, current).apply()
    }

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
     * Version sincrona (sin red, sin decodificar el bitmap completo) para
     * saber si la caratula de [song] ya esta disponible localmente
     * (memoria o disco). Se usa para calcular el estado "descargada /
     * pendiente" de cada cancion en Ajustes (ver
     * LyricsArtStatusRepository), donde hace falta revisar cientos de
     * canciones sin decodificar bitmaps que no se van a mostrar. Debe
     * llamarse desde un hilo de fondo (ahora hace una consulta a
     * MediaStore para resolver la ruta real del archivo, ver
     * [cacheKeyFor]).
     */
    fun hasCachedCoverOnDisk(context: Context, song: Song): Boolean {
        if (memoryCache.get(song.id) != null) return true
        val cacheKey = cacheKeyFor(context.applicationContext, song)
        val diskFile = File(cacheDir(context.applicationContext), "$cacheKey.jpg")
        return diskFile.exists()
    }

    /**
     * Si la app ya tiene el permiso de "Todos los archivos", graba [bitmap]
     * directo en el tag del archivo de audio de [song] ademas del cache
     * normal (memoria/disco), para que la caratula sobreviva a una
     * desinstalacion sin que el usuario tenga que entrar a Ajustes > Letras
     * y Caratulas a forzar la actualizacion. Se llama siempre desde un hilo
     * de fondo (aqui ya lo estamos: dentro del executor propio de este
     * repositorio). Si falla o no hay permiso, no pasa nada: la caratula se
     * queda igual disponible en el cache normal de la app.
     */
    private fun persistCoverToAudioFileIfPossible(context: Context, song: Song, bitmap: Bitmap) {
        if (!SongFileTagWriter.hasManageStoragePermission(context)) return
        SongFileTagWriter.writeToFile(context, song, coverBitmap = bitmap)
    }

    /**
     * Pide la caratula de [song]. Llama a [callback] en el hilo principal
     * SOLO si la encuentra (embebida, disco o red) y sigue siendo necesaria.
     * Si no hay caratula disponible, no llama a [callback]: quien la pidio
     * debe dejar el placeholder que ya tenia puesto.
     *
     * [isStillNeeded] es opcional y sirve para que quien pide la caratula
     * (tipicamente un RecyclerView.Adapter) avise si la vista que la pidio
     * ya se reciclo para otra cancion. Se chequea antes de tocar disco/red
     * y antes de entregar el resultado: si durante un scroll rapido la
     * vista ya no necesita esta caratula, la tarea se descarta enseguida en
     * vez de competir por un hilo del pool con las que si son visibles.
     *
     * FIX: antes esta funcion (usada por Home, listas, cola, widget,
     * notificacion, etc.) iba directo de "cache en disco" a "red" apenas no
     * habia nada en disco, sin revisar nunca la caratula que el propio
     * archivo de audio ya trae embebida (eso solo lo hacia
     * [loadCoverCacheOnly], usada unicamente por el reproductor). El
     * resultado: la primera vez que una cancion aparecia en esas pantallas,
     * se saltaba la caratula real del archivo y salia a buscar en
     * iTunes/Deezer, a veces trayendo una portada de otra cancion/artista
     * con nombre parecido -y esa portada equivocada quedaba guardada en
     * disco (y hasta escrita sobre el archivo real si hay permiso de
     * "Todos los archivos"), pisando la caratula original.
     *
     * Ahora, igual que en [loadCoverCacheOnly], si la cancion no tiene una
     * eleccion manual guardada (ver [isManualOverride]) se prioriza la
     * caratula embebida del archivo por sobre el cache de disco y por sobre
     * la red. Solo se sale a la red si el archivo no trae ninguna caratula
     * propia.
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
            var source = "NONE"
            try {
                val stillNeeded = synchronized(inFlight) {
                    pendingCallbacks[song.id]?.any { it.isStillNeeded() } == true
                }
                if (!stillNeeded) return@execute

                val cacheKey = cacheKeyFor(appContext, song)
                val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")
                val manualOverride = isManualOverride(appContext, song.id)

                // 1) Si no hay eleccion manual, la caratula embebida del
                // propio archivo tiene prioridad sobre disco y sobre red.
                val fromEmbedded = if (!manualOverride) {
                    EmbeddedMetadataReader.readArtwork(appContext, song)
                } else null

                if (fromEmbedded != null) {
                    saveToDisk(diskFile, fromEmbedded)
                }

                // 2) Cache de disco (solo si no habia embebida que mostrar).
                val fromDisk = if (fromEmbedded == null && diskFile.exists()) {
                    decodeSampledBitmapFromFile(diskFile)
                } else null

                // 3) Red, unico caso en que puede llegar una caratula que
                // no sea la del propio archivo.
                bitmap = fromEmbedded ?: fromDisk ?: run {
                    val needsNetwork = synchronized(inFlight) {
                        pendingCallbacks[song.id]?.any { it.isStillNeeded() } == true
                    }
                    if (needsNetwork) {
                        fetchFromNetwork(song)?.also { bmp ->
                            saveToDisk(diskFile, bmp)
                            // Descarga automatica desde red (lista, cola,
                            // notificacion, widget, etc.): se graba tambien
                            // en el archivo real para que sobreviva a una
                            // desinstalacion.
                            persistCoverToAudioFileIfPossible(appContext, song, bmp)
                        }
                    } else null
                }
                source = if (fromEmbedded != null) "EMBEDDED_FILE" else if (fromDisk != null) "DISK(cacheKey=$cacheKey)" else if (bitmap != null) "NETWORK" else "NONE"

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
     * al mantener presionada la caratula (ver AlbumArtPickerDialog) o
     * desde Ajustes > Letras y Caratulas.
     *
     * Antes de rendirse (memoria y disco sin nada), revisa si el propio
     * archivo de audio ya trae una caratula embebida (ver
     * EmbeddedMetadataReader): si la tiene, se le da prioridad sobre salir
     * a buscar en red y se guarda en este mismo cache de disco, para que
     * las siguientes veces se lea de ahi directo. Una sobreescritura
     * manual posterior (long-press o Ajustes) simplemente pisa ese mismo
     * archivo de cache, exactamente igual que con una caratula bajada de
     * red.
     */
    /**
     * A pedido: se le da prioridad a lo que el propio archivo trae embebido
     * por sobre cualquier cache (memoria o disco). Esto significa que CADA
     * llamada abre y parsea el archivo de audio completo con jaudiotagger
     * antes de mirar cualquier cache, incluso si la misma caratula ya se
     * mostro hace un segundo. Es intencional (decision del usuario), pero
     * tiene un costo real de I/O/CPU en cada scroll/cambio de cancion.
     *
     * Excepcion: si la cancion ya tiene una eleccion manual guardada (ver
     * [isManualOverride]), esa eleccion se respeta tal cual y no se vuelve
     * a mirar el archivo.
     */
    fun loadCoverCacheOnly(
        context: Context,
        song: Song,
        callback: Callback,
        isStillNeeded: () -> Boolean = { true }
    ) {
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
            var source = "NONE"
            try {
                val stillNeeded = synchronized(inFlight) {
                    pendingCallbacks[song.id]?.any { it.isStillNeeded() } == true
                }
                if (!stillNeeded) return@execute

                val cacheKey = cacheKeyFor(appContext, song)
                val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")
                val manualOverride = isManualOverride(appContext, song.id)

                if (manualOverride) {
                    // El usuario ya eligio esta caratula a mano: no se
                    // vuelve a mirar el archivo, se respeta memoria -> disco
                    // como antes.
                    val memHit = memoryCache.get(song.id)
                    if (memHit != null) {
                        bitmap = memHit
                        source = "MEMORY(manual)"
                    } else if (diskFile.exists()) {
                        bitmap = decodeSampledBitmapFromFile(diskFile)
                        source = "DISK(manual)"
                    }
                } else {
                    // 1) Lo que trae el archivo, primero.
                    bitmap = EmbeddedMetadataReader.readArtwork(appContext, song)

                    if (bitmap != null) {
                        source = "EMBEDDED_FILE"
                        // La guardamos en disco para que hasCachedCoverOnDisk()
                        // y el resto de la app sigan funcionando igual que antes.
                        saveToDisk(diskFile, bitmap)
                    } else {
                        // 2) Cache de memoria.
                        val memHit = memoryCache.get(song.id)
                        if (memHit != null) {
                            bitmap = memHit
                            source = "MEMORY"
                        } else if (diskFile.exists()) {
                            // 3) Cache de disco.
                            bitmap = decodeSampledBitmapFromFile(diskFile)
                            source = "DISK"
                        }
                    }
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
                val cacheKey = cacheKeyFor(appContext, song)
                val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")
                found = diskFile.exists()
                if (!found) {
                    val bitmap = fetchFromNetwork(song)
                    if (bitmap != null) {
                        saveToDisk(diskFile, bitmap)
                        persistCoverToAudioFileIfPossible(appContext, song, bitmap)
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
        val query = "${sanitizeArtistForQuery(song.artist, song.title)} ${song.title}".trim()
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
            val cacheKey = cacheKeyFor(appContext, song)
            val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")
            saveToDisk(diskFile, bitmap)
            memoryCache.put(song.id, bitmap)
            // Marca esta eleccion como manual para que loadCoverCacheOnly
            // ya no la pise sola con lo que traiga el archivo.
            markManualOverride(appContext, song.id)
            // Eleccion manual de caratula (picker de iTunes/Deezer): tambien
            // se graba en el archivo real, igual que una descarga automatica.
            persistCoverToAudioFileIfPossible(appContext, song, bitmap)
            mainHandler.post { callback.onCoverReady(bitmap) }
        }
    }

    /**
     * Igual que [applyOverride] pero SIN callback ni pasar por el executor:
     * pensada para cuando quien llama (ver
     * LyricsArtStatusRepository.computeStatus) ya esta corriendo en un
     * hilo de fondo y ya leyo [bitmap] de las etiquetas embebidas del
     * archivo (ver EmbeddedMetadataReader), asi que solo hace falta
     * guardarlo en el mismo cache de disco/memoria que usa el resto de la
     * app. Debe llamarse desde un hilo de fondo.
     */
    fun cacheEmbeddedArtwork(context: Context, song: Song, bitmap: Bitmap) {
        val appContext = context.applicationContext
        val diskFile = File(cacheDir(appContext), "${cacheKeyFor(appContext, song)}.jpg")
        saveToDisk(diskFile, bitmap)
        memoryCache.put(song.id, bitmap)
    }

    /**
     * Quita la caratula de [songId] de la cache de MEMORIA (no toca disco).
     * Se usa cuando el titulo/artista de la cancion cambia (ver
     * PlaylistDialogs.showEditSongMetadataDialog): la caratula en disco
     * queda asociada al nombre anterior (la clave se calcula con
     * artista+titulo, ver [cacheKeyFor]), asi que si no se limpia tambien
     * la de memoria, la app seguiria mostrando la caratula vieja hasta
     * reiniciarse aunque en disco ya no corresponda a nada.
     */
    fun invalidateMemory(songId: Long) {
        memoryCache.remove(songId)
    }

    /**
     * Igual que [prefetchCover], pero SIEMPRE vuelve a buscar en red y
     * sobreescribe lo que hubiera en disco, incluso si ya habia una
     * caratula guardada. La usa el boton "Redescargar y sobreescribir" de
     * LyricsArtStatusActivity para cuando el usuario ya edito el
     * titulo/artista (o simplemente quiere forzar una busqueda de nuevo)
     * sin tener que desinstalar la app.
     */
    fun forceRefreshCover(context: Context, song: Song, onComplete: (found: Boolean) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            var found = false
            try {
                val bitmap = fetchFromNetwork(song)
                if (bitmap != null) {
                    val cacheKey = cacheKeyFor(appContext, song)
                    val diskFile = File(cacheDir(appContext), "$cacheKey.jpg")
                    saveToDisk(diskFile, bitmap)
                    memoryCache.put(song.id, bitmap)
                    // "Redescargar y sobreescribir" es una accion explicita
                    // del usuario: se trata igual que una eleccion manual.
                    markManualOverride(appContext, song.id)
                    found = true
                }
            } catch (e: Exception) {
                // found se queda en false: no se encontro nada nuevo.
            } finally {
                val result = found
                mainHandler.post { onComplete(result) }
            }
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
        val query = "${sanitizeArtistForQuery(song.artist, song.title)} ${song.title}".trim()
        if (query.isBlank()) return null

        return fetchFromItunes(query, song) ?: fetchFromDeezer(query, song)
    }

    /**
     * Apps que descargan musica (ej. "Muka") a veces generan archivos con
     * metadata sucia donde el campo "artista" repite el titulo de la
     * cancion al final (ej. artist="Grupo MM, Bruce Wayne" para una
     * cancion title="Bruce Wayne"). Si esa query se manda tal cual a
     * iTunes/Deezer como "{artist} {title}", queda algo como
     * "Grupo MM, Bruce Wayne Bruce Wayne": el titulo aparece dos veces y la
     * API puede matchear una cancion real distinta que tambien se llama
     * igual pero es de otro artista/album (la API responde bien a lo que
     * se le pidio, el problema es la query).
     *
     * Si el artista termina con el titulo (sin distinguir mayusculas), se
     * recorta esa repeticion antes de concatenar. Si al recortar no queda
     * nada util (ej. el campo artista era solo el titulo repetido), se
     * devuelve el artista original tal cual para no mandar una query vacia
     * de artista.
     */
    private fun sanitizeArtistForQuery(artist: String, title: String): String {
        val artistTrim = artist.trim()
        val titleTrim = title.trim()
        if (artistTrim.isBlank() || titleTrim.isBlank()) return artist

        if (artistTrim.endsWith(titleTrim, ignoreCase = true)) {
            val withoutTitle = artistTrim.substring(0, artistTrim.length - titleTrim.length)
            val cleaned = withoutTitle.trim().trimEnd(',', ';', '-', '/').trim()
            if (cleaned.isNotBlank()) return cleaned
        }

        return artist
    }

    private fun fetchFromItunes(query: String, song: Song): Bitmap? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=1"
            val json = httpGetJson(url) ?: return null
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) {
                return null
            }

            val matched = results.getJSONObject(0)

            val artworkUrl = matched.optString("artworkUrl100", "")
            if (artworkUrl.isBlank()) return null

            // iTunes devuelve 100x100 por defecto; se pide una version mas grande.
            val highRes = artworkUrl.replace("100x100bb", "600x600bb")
            downloadBitmap(highRes)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFromDeezer(query: String, song: Song): Bitmap? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.deezer.com/search?q=$encoded&limit=1"
            val json = httpGetJson(url) ?: return null
            val data = json.optJSONArray("data") ?: return null
            if (data.length() == 0) {
                return null
            }

            val matchedTrack = data.getJSONObject(0)
            val album = matchedTrack.optJSONObject("album") ?: return null
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

    @Volatile private var legacyMigrationDone = false

    private fun cacheDir(context: Context): File {
        val dir = File(context.filesDir, STORE_DIR_NAME)
        migrateLegacyCacheIfNeeded(context, dir)
        return dir
    }

    /**
     * Copia (una sola vez por proceso) las caratulas que ya estuvieran
     * guardadas en la carpeta vieja (context.cacheDir) a la nueva carpeta
     * persistente, para no perder las que el usuario ya tenia descargadas
     * antes de este cambio. Si el sistema ya habia borrado esa carpeta
     * vieja, simplemente no hay nada que migrar.
     */
    private fun migrateLegacyCacheIfNeeded(context: Context, newDir: File) {
        if (legacyMigrationDone) return
        synchronized(this) {
            if (legacyMigrationDone) return
            legacyMigrationDone = true
            try {
                val legacyDir = File(context.cacheDir, LEGACY_CACHE_DIR_NAME)
                val legacyFiles = legacyDir.listFiles() ?: return
                if (legacyFiles.isEmpty()) return
                newDir.mkdirs()
                legacyFiles.forEach { legacyFile ->
                    val target = File(newDir, legacyFile.name)
                    if (!target.exists()) {
                        try {
                            legacyFile.copyTo(target, overwrite = false)
                        } catch (e: Exception) {
                            // Si una caratula puntual no se pudo copiar, se
                            // vuelve a descargar sola la proxima vez.
                        }
                    }
                }
            } catch (e: Exception) {
                // No habia carpeta vieja o no se pudo leer: no es grave,
                // las caratulas se volveran a descargar cuando hagan falta.
            }
        }
    }

    /**
     * Antes la clave del cache de disco se armaba solo con artista+titulo,
     * pensado para sobrevivir a que Android reasigne un _ID nuevo a la
     * misma cancion (ver SongIdMigrator). El efecto secundario: dos
     * canciones DISTINTAS con el mismo artista y titulo (una descarga
     * duplicada, una version diferente, un feat. con el mismo nombre...)
     * terminaban compartiendo la misma caratula en disco -- si le
     * cambiabas la caratula a una, la otra heredaba ese cambio sin que vos
     * lo hicieras.
     *
     * Se cambio a usar la ruta real del archivo (MediaStore.DATA), pero
     * eso solo no alcanza: apps que descargan musica (ej. "Muka") generan
     * el nombre de archivo de forma deterministica a partir de
     * titulo+artista, asi que si mas adelante se vuelve a descargar algo
     * con ese mismo nombre, el archivo fisico en esa ruta se sobreescribe
     * con contenido distinto pero la caratula vieja cacheada en disco
     * para esa ruta se le seguia sirviendo a la cancion nueva.
     *
     * Por eso ahora la clave tambien incluye la fecha de modificacion
     * (lastModified) del archivo: si el archivo en una ruta cambia
     * (re-descarga, sobreescritura), la clave cambia y el cache viejo
     * queda huerfano en vez de reutilizarse por error. Esto invalida de
     * una sola vez todo el cache existente (las claves viejas no tenian
     * fecha de modificacion, asi que no hay forma de migrarlas sin
     * arriesgarse a conservar justo las que ya estaban mal), pero es
     * necesario: no hay forma de saber si una caratula cacheada
     * previamente corresponde a la version actual del archivo o a una
     * version anterior ya reemplazada.
     *
     * Si no se puede resolver la ruta (por ejemplo, MediaStore todavia
     * no termino de indexar un archivo recien descargado -- pasa seguido
     * justo despues de una descarga, antes de que el escaneo de medios
     * termine), el fallback NO puede ser el viejo "artista+titulo" tal
     * cual: ese era justo el esquema que causaba que canciones distintas
     * con el mismo nombre compartieran caratula, y dos descargas
     * consecutivas del mismo nombre calan en este fallback si MediaStore
     * anda lento las dos veces. Por eso el fallback ahora incluye
     * ademas song.id y song.dateAdded (la fecha en que MediaStore indexo
     * esta fila en particular), que para una descarga nueva es un valor
     * que ninguna cancion anterior pudo haber tenido, y se le agrega un
     * prefijo para no coincidir nunca con una clave del esquema por ruta
     * ni con una clave del esquema viejo (pre-fix).
     */
    private fun cacheKeyFor(context: Context, song: Song): String {
        val path = resolveFilePath(context, song)
        val raw = if (path != null) {
            "$path|${fileLastModifiedSafe(path)}"
        } else {
            "nopath|${song.id}|${song.dateAdded}|${song.artist}|${song.title}"
        }.lowercase()
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        val key = digest.joinToString("") { "%02x".format(it) }
        return key
    }

    /**
     * lastModified() del archivo en [path], o 0L si no se puede leer
     * (archivo eliminado justo en este instante, permiso revocado, etc.).
     * Se usa solo como parte de la clave del cache, asi que un valor 0L
     * en el peor caso hace que esa cancion en particular se comporte como
     * antes de este cambio (clave estable mientras no cambie la ruta),
     * en vez de romper nada.
     */
    private fun fileLastModifiedSafe(path: String): Long {
        return try {
            File(path).lastModified()
        } catch (e: Exception) {
            0L
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveFilePath(context: Context, song: Song): String? {
        return try {
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            context.contentResolver.query(song.uri, projection, null, null, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (dataColumn >= 0 && cursor.moveToFirst()) cursor.getString(dataColumn) else null
            }
        } catch (e: Exception) {
            null
        }
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