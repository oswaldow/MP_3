package com.learnlayout.mp_3

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.learnlayout.mp_3.databinding.ActivityAddSongsToPlaylistBinding

/**
 * Pantalla para agregar canciones existentes del dispositivo a una playlist
 * ya creada. Se abre desde PlaylistDetailActivity (boton "Agregar
 * canciones"). Reutiliza SelectableSongAdapter: cada fila alterna su
 * seleccion al tocarla, y la barra inferior confirma el agregado en bloque.
 */
class AddSongsToPlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddSongsToPlaylistBinding

    private lateinit var playlistId: String
    private var allSongs: List<Song> = emptyList()
    private val selectedSongIds = mutableSetOf<Long>()
    private lateinit var selectableAdapter: SelectableSongAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddSongsToPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        if (id == null) {
            finish()
            return
        }
        playlistId = id

        binding.btnBack.setOnClickListener { finish() }

        setupSongsList()
        setupSearch()

        binding.btnConfirmAddSongs.setOnClickListener { confirmAddSongs() }
    }

    private fun setupSongsList() {
        // Excluimos las canciones que ya estan en la playlist: no tiene
        // caso volver a mostrarlas para agregarlas de nuevo.
        val playlist = PlaylistRepository.getPlaylistById(this, playlistId)
        val alreadyInPlaylist = playlist?.songIds?.toSet() ?: emptySet()

        val deviceSongs = SongRepository.getAllSongs(this).map { SongMetadataRepository.apply(this, it) }
        allSongs = deviceSongs.filter { it.id !in alreadyInPlaylist }

        binding.rvSelectableSongs.layoutManager = LinearLayoutManager(this)
        selectableAdapter = SelectableSongAdapter(
            songs = allSongs,
            isSelected = { songId -> selectedSongIds.contains(songId) },
            onToggle = { song -> toggleSelection(song) }
        )
        binding.rvSelectableSongs.adapter = selectableAdapter

        binding.tvNoSongsFound.visibility = if (allSongs.isEmpty()) View.VISIBLE else View.GONE
        binding.rvSelectableSongs.visibility = if (allSongs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setupSearch() {
        binding.etSearchAddSongs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterSongs(s?.toString().orEmpty())
            }
        })
    }

    private fun filterSongs(query: String) {
        val filtered = if (query.isBlank()) {
            allSongs
        } else {
            allSongs.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true)
            }
        }
        selectableAdapter.updateData(filtered)
        binding.tvNoSongsFound.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.rvSelectableSongs.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleSelection(song: Song) {
        if (selectedSongIds.contains(song.id)) {
            selectedSongIds.remove(song.id)
        } else {
            selectedSongIds.add(song.id)
        }
        selectableAdapter.refreshSelectionStates()
        updateConfirmBar()
    }

    private fun updateConfirmBar() {
        val count = selectedSongIds.size
        binding.tvSelectedCount.text = when (count) {
            0 -> "0 canciones seleccionadas"
            1 -> "1 cancion seleccionada"
            else -> "$count canciones seleccionadas"
        }
        binding.btnConfirmAddSongs.isEnabled = count > 0
        binding.btnConfirmAddSongs.alpha = if (count > 0) 1f else 0.5f
    }

    private fun confirmAddSongs() {
        if (selectedSongIds.isEmpty()) return

        selectedSongIds.forEach { songId ->
            PlaylistRepository.addSongToPlaylist(this, playlistId, songId)
        }

        val count = selectedSongIds.size
        val message = if (count == 1) "1 cancion agregada" else "$count canciones agregadas"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        finish()
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
    }
}