package com.learnlayout.mp_3

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var btnPlayAll: ImageButton
    private lateinit var tvPlaylistTitle: TextView
    private lateinit var tvEmptyPlaylist: TextView
    private lateinit var rvPlaylistSongs: RecyclerView

    private lateinit var playlistId: String
    private var playlistSongs: MutableList<Song> = mutableListOf()
    private lateinit var songAdapter: SongAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_detail)

        bindViews()

        val id = intent.getStringExtra("playlist_id")
        if (id == null) {
            finish()
            return
        }
        playlistId = id

        btnBack.setOnClickListener { finish() }

        btnPlayAll.setOnClickListener {
            if (playlistSongs.isNotEmpty()) {
                openPlayer(0)
            }
        }

        rvPlaylistSongs.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(
            emptyList(),
            onItemClick = { position -> openPlayer(position) },
            onMenuClick = { position -> confirmRemoveSong(position) }
        )
        rvPlaylistSongs.adapter = songAdapter

        loadPlaylistSongs()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnPlayAll = findViewById(R.id.btnPlayAll)
        tvPlaylistTitle = findViewById(R.id.tvPlaylistTitle)
        tvEmptyPlaylist = findViewById(R.id.tvEmptyPlaylist)
        rvPlaylistSongs = findViewById(R.id.rvPlaylistSongs)
    }

    private fun loadPlaylistSongs() {
        val playlist = PlaylistRepository.getPlaylistById(this, playlistId)
        if (playlist == null) {
            finish()
            return
        }

        tvPlaylistTitle.text = playlist.name

        val allSongs = SongRepository.getAllSongs(this)
        val songsById = allSongs.associateBy { it.id }

        playlistSongs = playlist.songIds.mapNotNull { songsById[it] }.toMutableList()

        songAdapter.updateData(playlistSongs)

        val hasSongs = playlistSongs.isNotEmpty()
        tvEmptyPlaylist.visibility = if (hasSongs) View.GONE else View.VISIBLE
        rvPlaylistSongs.visibility = if (hasSongs) View.VISIBLE else View.GONE
    }

    private fun confirmRemoveSong(position: Int) {
        val song = playlistSongs.getOrNull(position) ?: return

        AlertDialog.Builder(this, R.style.RoundedAlertDialog)
            .setTitle("Quitar cancion")
            .setMessage("Quitar \"${song.title}\" de esta playlist?")
            .setPositiveButton("Quitar") { _, _ ->
                PlaylistRepository.removeSongFromPlaylist(this, playlistId, song.id)
                loadPlaylistSongs()
                Toast.makeText(this, "Cancion eliminada de la playlist", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openPlayer(startIndex: Int) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putParcelableArrayListExtra("song_list", ArrayList(playlistSongs))
        intent.putExtra("start_index", startIndex)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        loadPlaylistSongs()
    }
}