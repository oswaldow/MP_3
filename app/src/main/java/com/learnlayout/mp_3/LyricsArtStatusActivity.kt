package com.learnlayout.mp_3

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.learnlayout.mp_3.databinding.ActivityLyricsArtStatusBinding

class LyricsArtStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLyricsArtStatusBinding
    private lateinit var statusAdapter: LyricsArtStatusAdapter

    private var allItems: List<SongDownloadStatus> = emptyList()
    private var showMissingOnly = false

    // Evita lanzar dos redescargas masivas al mismo tiempo, y evita que un
    // toque de redescarga individual se mezcle con una masiva en curso.
    private var isForceRedownloading = false
    private val itemsRefreshingIndividually = mutableSetOf<Long>()

    private val playlistDialogs: PlaylistDialogs by lazy {
        PlaylistDialogs(
            context = this,
            isPlaylistsTabActive = { false },
            onPlaylistsChanged = {},
            onSongMetadataChanged = { loadStatus() },
            onDeleteSongFromDevice = {}
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLyricsArtStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.rvStatusList.layoutManager = LinearLayoutManager(this)
        statusAdapter = LyricsArtStatusAdapter(
            items = emptyList(),
            onItemClick = { item -> openPickerFor(item) },
            onEditClick = { item -> playlistDialogs.showEditSongMetadataDialog(item.song) },
            onForceRefreshClick = { item -> forceRefreshSingleSong(item) }
        )
        binding.rvStatusList.adapter = statusAdapter

        binding.btnForceRedownloadAll.setOnClickListener { forceRedownloadAll() }

        setupSearch()
        setupMissingOnlyToggle()
        setupSwipeToRefresh()

        loadStatus()
    }

    override fun onResume() {
        super.onResume()
        if (allItems.isNotEmpty() && !isForceRedownloading) {
            loadStatus()
        }
    }

    /**
     * "Tirar para abajo" sobre la lista vuelve a calcular el estado de
     * letras/caratulas al instante, igual que el boton "Actualizar" de
     * arriba pero con el gesto e icono estandar. A diferencia de la carga
     * inicial, esta no tapa la lista con el ProgressBar grande (silently =
     * true): la lista actual se queda visible mientras se recalcula, y solo
     * se ve el icono circular de "actualizando" hasta que termine.
     */
    private fun setupSwipeToRefresh() {
        binding.swipeRefreshStatusList.setColorSchemeResources(R.color.spotify_green)
        binding.swipeRefreshStatusList.setProgressBackgroundColorSchemeResource(R.color.spotify_card)

        binding.swipeRefreshStatusList.setOnRefreshListener {
            loadStatus(silently = true)
        }
    }

    private fun setupSearch() {
        binding.etSearchStatus.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilters()
            }
        })
    }

    private fun setupMissingOnlyToggle() {
        binding.chipShowMissingOnly.setOnClickListener {
            showMissingOnly = !showMissingOnly
            binding.chipShowMissingOnly.setBackgroundResource(
                if (showMissingOnly) R.drawable.bg_chip_eq_preset_selected
                else R.drawable.bg_chip_eq_preset_unselected
            )
            applyFilters()
        }
    }

    /**
     * [silently] evita el ProgressBar grande de pantalla completa (que
     * ademas esconde la lista mientras carga): se usa cuando el refresh
     * viene del gesto de "tirar para abajo" (ver setupSwipeToRefresh), que
     * ya muestra su propio icono de actualizando sin tapar la lista actual.
     */
    private fun loadStatus(silently: Boolean = false) {
        if (!silently) {
            binding.progressLoadingStatus.visibility = View.VISIBLE
            binding.rvStatusList.visibility = View.GONE
            binding.tvNoStatusResults.visibility = View.GONE
        }

        AppExecutors.runInBackground {
            val summary = LyricsArtStatusRepository.computeStatus(this)
            AppExecutors.runOnMain {
                allItems = summary.items
                binding.progressLoadingStatus.visibility = View.GONE
                binding.swipeRefreshStatusList.isRefreshing = false
                updateSummaryCounts(summary)
                applyFilters()
            }
        }
    }

    private fun updateSummaryCounts(summary: DownloadStatusSummary) {
        binding.tvLyricsSummaryCount.text = "${summary.lyricsCount}/${summary.total}"
        binding.tvArtSummaryCount.text = "${summary.artCount}/${summary.total}"
    }

    private fun openPickerFor(item: SongDownloadStatus) {
        AlbumArtPickerDialog(
            context = this,
            song = item.song,
            onCoverChosen = { chosenSong, bitmap ->
                AlbumArtRepository.applyOverride(
                    this, chosenSong, bitmap,
                    object : AlbumArtRepository.Callback {
                        override fun onCoverReady(bmp: android.graphics.Bitmap) {
                            markItemUpdated(chosenSong.id, hasArt = true)
                            Toast.makeText(this@LyricsArtStatusActivity, "Caratula guardada", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            },
            onLyricsChosen = { chosenSong, result ->
                SavedLyricsRepository.save(this, chosenSong.id, result)
                markItemUpdated(chosenSong.id, hasLyrics = true)
                Toast.makeText(this, "Letra guardada", Toast.LENGTH_SHORT).show()
            }
        ).show()
    }

    /**
     * Antes de "Actualizar" (individual o masivo) hace falta el permiso de
     * "Todos los archivos" para poder escribir en las canciones reales.
     * Si no esta concedido, se explica por que y se manda a la pantalla del
     * sistema para activarlo; la accion de actualizar se cancela para que
     * el usuario la vuelva a tocar despues de conceder el permiso.
     */
    private fun ensureFileWritePermission(onGranted: () -> Unit) {
        if (SongFileTagWriter.hasManageStoragePermission(this)) {
            onGranted()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Permiso necesario")
            .setMessage(
                "Para guardar la caratula y la letra dentro del archivo de la " +
                        "cancion (y que no se pierdan si reinstalas la app), " +
                        "MP_3 necesita el permiso \"Acceso a todos los archivos\". " +
                        "Actívalo y vuelve a tocar Actualizar."
            )
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                SongFileTagWriter.requestManageStoragePermission(this)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Vuelve a buscar en red la caratula y la letra de UNA sola cancion
     * (usando su titulo/artista actual, ya con overrides aplicados) y
     * sobreescribe lo que hubiera guardado, sin importar si ya estaba
     * "completa". Pensado para usarse justo despues de editar el
     * nombre/artista de la cancion o de elegir una caratula que no quedo
     * bien: en vez de tener que desinstalar la app o esperar a la
     * descarga masiva, un solo toque fuerza la actualizacion de esa
     * cancion.
     *
     * Ademas de guardar en el almacenamiento privado de la app (como
     * antes), esta version escribe la caratula y la letra directamente
     * dentro del archivo de audio (ver SongFileTagWriter), para que
     * sobrevivan a una desinstalacion.
     */
    private fun forceRefreshSingleSong(item: SongDownloadStatus) {
        ensureFileWritePermission {
            doForceRefreshSingleSong(item)
        }
    }

    private fun doForceRefreshSingleSong(item: SongDownloadStatus) {
        val songId = item.song.id
        if (!itemsRefreshingIndividually.add(songId)) return

        Toast.makeText(this, "Actualizando \"${item.song.title}\"...", Toast.LENGTH_SHORT).show()

        var artUpdated = false
        var lyricsUpdated = false

        fun finishIfReady() {
            // Se espera a que terminen tanto la caratula como la letra
            // antes de refrescar la fila, para no repintar dos veces.
            itemsRefreshingIndividually.remove(songId)
            markItemUpdated(songId, hasLyrics = lyricsUpdated, hasArt = artUpdated)

            writeUpdatedTagsToFile(item.song) {
                Toast.makeText(
                    this,
                    "\"${item.song.title}\" actualizada",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        AlbumArtRepository.forceRefreshCover(this, item.song) { found ->
            artUpdated = found || item.hasArt

            val durationSeconds = item.song.duration / 1000
            LyricsRepository.fetch(
                item.song.title,
                item.song.artist,
                durationSeconds,
                object : LyricsRepository.LyricsCallback {
                    override fun onSuccess(result: LyricsResult) {
                        val hasLyrics = !result.isInstrumental &&
                                (!result.syncedLines.isNullOrEmpty() || !result.plainLyrics.isNullOrBlank())
                        if (hasLyrics) {
                            SavedLyricsRepository.save(this@LyricsArtStatusActivity, songId, result)
                        }
                        lyricsUpdated = hasLyrics || item.hasLyrics
                        finishIfReady()
                    }

                    override fun onError(message: String) {
                        lyricsUpdated = item.hasLyrics
                        finishIfReady()
                    }
                }
            )
        }
    }

    /**
     * Version masiva de [forceRefreshSingleSong]: recorre TODAS las
     * canciones (no solo las filtradas en pantalla) y sobreescribe la
     * caratula y la letra de cada una segun su titulo/artista actual, sin
     * saltarse las que ya estuvieran "completas" (a diferencia del boton
     * de descarga de Ajustes, que si se las salta). Sirve para aplicar de
     * una sola vez varias ediciones de nombre/caratula que se hayan hecho
     * antes. Tambien escribe cada resultado dentro del archivo de audio
     * correspondiente (ver SongFileTagWriter).
     */
    private fun forceRedownloadAll() {
        ensureFileWritePermission {
            doForceRedownloadAll()
        }
    }

    private fun doForceRedownloadAll() {
        if (isForceRedownloading) return
        if (allItems.isEmpty()) {
            Toast.makeText(this, "No se encontraron canciones", Toast.LENGTH_SHORT).show()
            return
        }

        isForceRedownloading = true
        binding.btnForceRedownloadAll.isEnabled = false
        binding.progressForceRedownloadAll.visibility = View.VISIBLE

        val songs = allItems.map { it.song }
        var index = 0
        var lyricsUpdatedCount = 0
        var artUpdatedCount = 0

        fun finishAll() {
            isForceRedownloading = false
            binding.btnForceRedownloadAll.isEnabled = true
            binding.progressForceRedownloadAll.visibility = View.GONE
            binding.tvForceRedownloadSummary.text =
                "Vuelve a buscar letra y caratula con el nombre actual de cada cancion"
            Toast.makeText(
                this,
                "Listo: $lyricsUpdatedCount letras y $artUpdatedCount caratulas actualizadas de ${songs.size}",
                Toast.LENGTH_LONG
            ).show()
            loadStatus()
        }

        lateinit var processNext: () -> Unit

        fun fetchLyricsForCurrentSong(song: Song) {
            val durationSeconds = song.duration / 1000
            LyricsRepository.fetch(song.title, song.artist, durationSeconds, object : LyricsRepository.LyricsCallback {
                override fun onSuccess(result: LyricsResult) {
                    val hasLyrics = !result.isInstrumental &&
                            (!result.syncedLines.isNullOrEmpty() || !result.plainLyrics.isNullOrBlank())
                    if (hasLyrics) {
                        SavedLyricsRepository.save(this@LyricsArtStatusActivity, song.id, result)
                        lyricsUpdatedCount++
                    }
                    writeUpdatedTagsToFile(song) {
                        index++
                        processNext()
                    }
                }

                override fun onError(message: String) {
                    writeUpdatedTagsToFile(song) {
                        index++
                        processNext()
                    }
                }
            })
        }

        processNext = {
            if (index >= songs.size) {
                finishAll()
            } else {
                val song = songs[index]
                binding.tvForceRedownloadSummary.text = "Actualizando ${index + 1}/${songs.size}: ${song.title}"

                AlbumArtRepository.forceRefreshCover(this, song) { found ->
                    if (found) artUpdatedCount++
                    fetchLyricsForCurrentSong(song)
                }
            }
        }

        processNext()
    }

    /**
     * Toma lo que haya quedado guardado en el almacenamiento privado
     * (caratula en memoria/disco via AlbumArtRepository, letra en
     * SavedLyricsRepository -sea que se acaba de actualizar o ya estuviera
     * de antes-) y lo escribe tambien dentro del archivo de audio real.
     * Se hace en el hilo de fondo compartido porque jaudiotagger hace I/O
     * bloqueante. [onDone] siempre se llama al final, en el hilo principal,
     * haya o no haya logrado escribir algo (por ejemplo si el permiso de
     * Todos los archivos se revoco justo despues del chequeo inicial).
     */
    private fun writeUpdatedTagsToFile(song: Song, onDone: () -> Unit) {
        val cover = AlbumArtRepository.getCachedCover(song)
        val lyrics = SavedLyricsRepository.getSavedLyrics(this, song.id)

        if (cover == null && lyrics == null) {
            onDone()
            return
        }

        AppExecutors.runInBackground {
            val wrote = SongFileTagWriter.writeToFile(this, song, cover, lyrics)
            AppExecutors.runOnMain {
                if (!wrote) {
                    Toast.makeText(
                        this,
                        "No se pudo guardar en el archivo de \"${song.title}\" (revisa el permiso de Todos los archivos)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                onDone()
            }
        }
    }

    private fun markItemUpdated(songId: Long, hasLyrics: Boolean? = null, hasArt: Boolean? = null) {
        allItems = allItems.map { existing ->
            if (existing.song.id == songId) {
                existing.copy(
                    hasLyrics = hasLyrics ?: existing.hasLyrics,
                    hasArt = hasArt ?: existing.hasArt
                )
            } else {
                existing
            }
        }
        updateSummaryCounts(DownloadStatusSummary(allItems))
        applyFilters()
    }

    private fun applyFilters() {
        val query = binding.etSearchStatus.text?.toString().orEmpty()

        var filtered = allItems
        if (showMissingOnly) {
            filtered = filtered.filter { !it.isComplete }
        }
        if (query.isNotBlank()) {
            filtered = filtered.filter {
                it.song.title.contains(query, ignoreCase = true) ||
                        it.song.artist.contains(query, ignoreCase = true)
            }
        }

        statusAdapter.updateData(filtered)

        val isEmpty = filtered.isEmpty()
        binding.tvNoStatusResults.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvStatusList.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
}