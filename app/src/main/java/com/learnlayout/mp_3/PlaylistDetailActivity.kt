package com.learnlayout.mp_3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    // Conexion directa al MusicService: reutilizamos el mismo reproductor
    // (el de SongListActivity, con letras/waveform) en lugar de abrir una
    // pantalla de player aparte (MainActivity, el reproductor viejo).
    // Solo necesitamos el service para armar la cola y arrancar la
    // reproduccion; el panel visual lo dibuja SongListActivity al volver.
    private var musicService: MusicService? = null
    private var isBound = false
    private var pendingPlaylist: List<Song>? = null
    private var pendingStartIndex: Int = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true

            val pending = pendingPlaylist
            if (pending != null) {
                pendingPlaylist = null
                musicService?.setPlaylist(pending, pendingStartIndex)
                returnToPlayer()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

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

        val serviceIntent = Intent(this, MusicService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnPlayAll = findViewById(R.id.btnPlayAll)
        tvPlaylistTitle = findViewById(R.id.tvPlaylistTitle)
        tvEmptyPlaylist = findViewById(R.id.tvEmptyPlaylist)
        rvPlaylistSongs = findViewById(R.id.rvPlaylistSongs)
    }

    private fun isAutoPlaylist(): Boolean {
        return playlistId == SongListActivity.RECENT_PLAYLIST_ID ||
                playlistId == SongListActivity.MOST_PLAYED_PLAYLIST_ID
    }

    private fun loadPlaylistSongs() {
        val allSongs = SongRepository.getAllSongs(this).map { SongMetadataRepository.apply(this, it) }
        val songsById = allSongs.associateBy { it.id }

        if (isAutoPlaylist()) {
            // Playlists automaticas de historial: no viven en
            // PlaylistRepository, se recalculan desde PlayCountRepository.
            val songIds = when (playlistId) {
                SongListActivity.RECENT_PLAYLIST_ID ->
                    PlayCountRepository.getRecentlyPlayedSongIds(this)
                else ->
                    PlayCountRepository.getMostPlayedSongIds(this)
            }

            tvPlaylistTitle.text = when (playlistId) {
                SongListActivity.RECENT_PLAYLIST_ID -> SongListActivity.RECENT_PLAYLIST_NAME
                else -> SongListActivity.MOST_PLAYED_PLAYLIST_NAME
            }

            playlistSongs = songIds.mapNotNull { songsById[it] }.toMutableList()
        } else {
            val playlist = PlaylistRepository.getPlaylistById(this, playlistId)
            if (playlist == null) {
                finish()
                return
            }

            tvPlaylistTitle.text = playlist.name
            playlistSongs = playlist.songIds.mapNotNull { songsById[it] }.toMutableList()
        }

        songAdapter.updateData(playlistSongs)

        val hasSongs = playlistSongs.isNotEmpty()
        tvEmptyPlaylist.visibility = if (hasSongs) View.GONE else View.VISIBLE
        rvPlaylistSongs.visibility = if (hasSongs) View.VISIBLE else View.GONE
    }

    private fun confirmRemoveSong(position: Int) {
        val song = playlistSongs.getOrNull(position) ?: return

        if (isAutoPlaylist()) {
            // Esta lista se arma sola con el historial de reproduccion, no
            // se pueden quitar canciones a mano.
            Toast.makeText(
                this,
                "Esta lista se genera automaticamente con tu historial",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

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
        val songs = playlistSongs.toList()
        val service = musicService
        if (service != null) {
            // Si la cancion tocada ya es la que esta sonando, no la
            // reiniciamos con setPlaylist: solo volvemos al reproductor
            // tal como esta.
            val tappedSong = songs.getOrNull(startIndex)
            val isSameSongPlaying = tappedSong != null && service.getCurrentSong()?.id == tappedSong.id
            if (!isSameSongPlaying) {
                service.setPlaylist(songs, startIndex)
            }
            returnToPlayer()
        } else {
            // El service aun no esta conectado (poco probable, se conecta
            // muy rapido en onCreate): guardamos la seleccion y la
            // aplicamos en cuanto llegue onServiceConnected.
            pendingPlaylist = songs
            pendingStartIndex = startIndex
        }
    }

    private fun returnToPlayer() {
        // Le avisamos a SongListActivity (que sigue viva debajo en el
        // back stack) que debe expandir su panel de reproductor al volver
        // a primer plano, y cerramos esta pantalla para regresar a ella.
        SongListActivity.expandPlayerOnResume = true
        finish()
    }

    override fun onResume() {
        super.onResume()
        loadPlaylistSongs()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}