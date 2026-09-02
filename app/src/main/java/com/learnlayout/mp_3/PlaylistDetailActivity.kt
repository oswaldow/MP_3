package com.learnlayout.mp_3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.learnlayout.mp_3.databinding.ActivityPlaylistDetailBinding

class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding

    private lateinit var playlistId: String
    private var playlistSongs: MutableList<Song> = mutableListOf()
    private lateinit var songAdapter: SongAdapter
    private var touchHelper: ItemTouchHelper? = null

    // ---------- Fondo dinamico (Material You + destellos, igual al Home) ----------
    // Preferimos la cancion que esta sonando ahora mismo (mismo criterio
    // vivo que el resto de la app); si no hay nada sonando, usamos la
    // primera cancion de esta playlist como referencia visual.
    private val ambientBackground: AmbientBackgroundController by lazy {
        AmbientBackgroundController(this, binding.root)
    }

    private fun updateAmbientBackground() {
        val song = musicService?.getCurrentSong() ?: playlistSongs.firstOrNull()
        ambientBackground.updateForSong(song)
    }

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

            updateAmbientBackground()

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
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra("playlist_id")
        if (id == null) {
            finish()
            return
        }
        playlistId = id

        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayAll.setOnClickListener {
            if (playlistSongs.isNotEmpty()) {
                openPlayer(0)
            }
        }

        // Recientes y Mas escuchadas se arman solas con el historial: no
        // tiene caso ofrecer "agregar canciones" a mano ahi.
        binding.btnAddSongs.visibility = if (isAutoPlaylist()) View.GONE else View.VISIBLE
        binding.btnAddSongs.setOnClickListener {
            val intent = Intent(this, AddSongsToPlaylistActivity::class.java)
            intent.putExtra(AddSongsToPlaylistActivity.EXTRA_PLAYLIST_ID, playlistId)
            startActivity(intent)
        }

        binding.rvPlaylistSongs.layoutManager = LinearLayoutManager(this)

        // Reordenar a mano solo tiene sentido en playlists propias: las
        // automaticas (Recientes/Mas escuchadas) recalculan su propio orden
        // solas y no aceptarian un orden manual persistente.
        val canReorder = !isAutoPlaylist()
        songAdapter = SongAdapter(
            emptyList(),
            onItemClick = { position -> openPlayer(position) },
            onMenuClick = { position -> confirmRemoveSong(position) },
            reorderable = canReorder,
            onMoveFinished = { _, _ -> persistCurrentOrder() }
        )
        binding.rvPlaylistSongs.adapter = songAdapter

        // El ItemTouchHelper se adjunta siempre (antes solo se creaba si
        // canReorder era true), porque el swipe a la derecha para "sonar a
        // continuacion" debe funcionar tambien en playlists automaticas
        // (Recientes/Mas escuchadas), que no permiten reordenar pero si
        // agregar a la cola.
        val callback = PlaylistSongTouchHelperCallback(
            adapter = songAdapter,
            dragEnabled = canReorder,
            onSwipeToPlayNext = { position ->
                val song = playlistSongs.getOrNull(position) ?: return@PlaylistSongTouchHelperCallback
                musicService?.addToPlayNext(song)
                Toast.makeText(this, "Sonará a continuación", Toast.LENGTH_SHORT).show()
            }
        )
        val helper = ItemTouchHelper(callback)
        helper.attachToRecyclerView(binding.rvPlaylistSongs)
        touchHelper = helper
        if (canReorder) {
            songAdapter.dragStartListener = { viewHolder -> helper.startDrag(viewHolder) }
        }

        loadPlaylistSongs()

        val serviceIntent = Intent(this, MusicService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun isAutoPlaylist(): Boolean {
        return playlistId == SongListActivity.RECENT_PLAYLIST_ID ||
                playlistId == SongListActivity.MOST_PLAYED_PLAYLIST_ID
    }

    /** Resultado de armar la lista de esta playlist, calculado en el hilo de fondo. */
    private data class LoadedPlaylist(val title: String, val songs: List<Song>)

    /**
     * SongRepository.getAllSongs() consulta MediaStore y le aplica a cada
     * fila una limpieza de artista con regex; con una biblioteca grande
     * eso no es instantaneo. Antes esto corria entero en el hilo
     * principal, y ademas se repetia en CADA onResume() (o sea, cada vez
     * que volvias a esta pantalla desde el reproductor, o despues de
     * agregar/quitar una cancion), lo que se sentia como una traba
     * perceptible al entrar y salir. Ahora el escaneo + filtrado corre en
     * AppExecutors.runInBackground y solo el resultado ya calculado se
     * aplica a las vistas en el hilo principal.
     */
    private fun loadPlaylistSongs() {
        AppExecutors.runInBackground {
            val allSongs = SongRepository.getAllSongs(this).map { SongMetadataRepository.apply(this, it) }
            val songsById = allSongs.associateBy { it.id }

            val result: LoadedPlaylist? = if (isAutoPlaylist()) {
                // Playlists automaticas de historial: no viven en
                // PlaylistRepository, se recalculan desde PlayCountRepository.
                // Limite explicito de 20: debe coincidir siempre con el
                // limite que usa HomeController.refresh() para el contador
                // del Home. Antes esta pantalla llamaba sin limite (caia al
                // default de PlayCountRepository, 50), por lo que la lista
                // completa podia mostrar mas canciones que el numero
                // anunciado en el Home (26 vs 20).
                val songIds = when (playlistId) {
                    SongListActivity.RECENT_PLAYLIST_ID ->
                        PlayCountRepository.getRecentlyPlayedSongIds(this, 20)
                    else ->
                        PlayCountRepository.getMostPlayedSongIds(this, 20)
                }

                val title = when (playlistId) {
                    SongListActivity.RECENT_PLAYLIST_ID -> SongListActivity.RECENT_PLAYLIST_NAME
                    else -> SongListActivity.MOST_PLAYED_PLAYLIST_NAME
                }

                LoadedPlaylist(title, songIds.mapNotNull { songsById[it] })
            } else {
                val playlist = PlaylistRepository.getPlaylistById(this, playlistId)
                if (playlist == null) {
                    null
                } else {
                    LoadedPlaylist(playlist.name, playlist.songIds.mapNotNull { songsById[it] })
                }
            }

            AppExecutors.runOnMain {
                // La pantalla pudo cerrarse mientras esto corria en el
                // hilo de fondo (por ejemplo, el usuario toco "atras"
                // antes de que terminara el escaneo): no tocar binding
                // de una Activity ya destruida.
                if (isFinishing || isDestroyed) return@runOnMain

                if (result == null) {
                    finish()
                    return@runOnMain
                }

                binding.tvPlaylistTitle.text = result.title
                playlistSongs = result.songs.toMutableList()
                songAdapter.updateData(playlistSongs)

                val hasSongs = playlistSongs.isNotEmpty()
                binding.tvEmptyPlaylist.visibility = if (hasSongs) View.GONE else View.VISIBLE
                binding.rvPlaylistSongs.visibility = if (hasSongs) View.VISIBLE else View.GONE

                updateAmbientBackground()
            }
        }
    }

    /** Se llama cuando el usuario suelta una cancion arrastrada: guarda el orden que quedo en el adapter. */
    private fun persistCurrentOrder() {
        val newOrder = songAdapter.getCurrentList()
        playlistSongs = newOrder.toMutableList()
        PlaylistRepository.reorderPlaylistSongs(this, playlistId, newOrder.map { it.id })
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