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
            onEditClick = { item -> playlistDialogs.showEditSongMetadataDialog(item.song) }
        )
        binding.rvStatusList.adapter = statusAdapter

        setupSearch()
        setupMissingOnlyToggle()
        setupSwipeToRefresh()

        loadStatus()
    }

    override fun onResume() {
        super.onResume()
        if (allItems.isNotEmpty()) {
            loadStatus()
        }
    }

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
                // applyOverride ya se encarga de embeber la caratula en el
                // archivo real internamente (persistCoverToAudioFileIfPossible).
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
                // A diferencia de la caratula, guardar la letra aqui no
                // la embebia en el archivo real (a diferencia de
                // SongListActivity y LyricsActivity, que si lo hacen).
                // Se agrega el mismo paso para que quede consistente.
                persistLyricsToAudioFileIfPossible(chosenSong, result)
                markItemUpdated(chosenSong.id, hasLyrics = true)
                Toast.makeText(this, "Letra guardada", Toast.LENGTH_SHORT).show()
            }
        ).show()
    }

    /**
     * Si la app ya tiene el permiso de "Todos los archivos", graba
     * [result] directo dentro del archivo de audio de [song], ademas del
     * guardado normal en SavedLyricsRepository. Mismo patron que usan
     * SongListActivity y LyricsActivity. Si falla o no hay permiso, no
     * pasa nada: la letra se queda igual disponible en el cache normal
     * de la app (y la palomita en esta pantalla sigue reflejando eso).
     */
    private fun persistLyricsToAudioFileIfPossible(song: Song, result: LyricsResult) {
        if (!SongFileTagWriter.hasManageStoragePermission(this)) return
        val appContext = applicationContext
        AppExecutors.runInBackground {
            SongFileTagWriter.writeToFile(appContext, song, lyricsResult = result)
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