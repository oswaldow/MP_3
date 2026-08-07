package com.learnlayout.mp_3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

class LyricsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvMessage: TextView
    private lateinit var rvLyrics: RecyclerView

    private var musicService: MusicService? = null
    private var isBound = false

    private var song: Song? = null
    private var syncedAdapter: LyricsLineAdapter? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            val service = musicService
            val adapter = syncedAdapter
            if (service != null && adapter != null) {
                val newIndex = adapter.updateActiveLine(service.getCurrentPosition().toLong())
                if (newIndex >= 0) scrollToLine(newIndex)
            }
            uiHandler.postDelayed(this, 400)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.MusicBinder).getService()
            isBound = true
            uiHandler.post(syncRunnable)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics)

        btnBack = findViewById(R.id.btnLyricsBack)
        tvTitle = findViewById(R.id.tvLyricsTitle)
        progressBar = findViewById(R.id.progressLyrics)
        tvMessage = findViewById(R.id.tvLyricsMessage)
        rvLyrics = findViewById(R.id.rvLyrics)

        rvLyrics.layoutManager = LinearLayoutManager(this)
        // Sin animador: evita que el redimensionado de la linea activa
        // (notifyItemChanged) pelee con el smooth-scroll y lo haga
        // sobrepasar la posicion deseada.
        rvLyrics.itemAnimator = null

        btnBack.setOnClickListener { finish() }

        song = intent.getParcelableExtra("song")
        tvTitle.text = song?.title ?: "Letra"

        val intentSong = song
        if (intentSong == null) {
            showMessage("No se pudo identificar la canción")
            return
        }

        bindService(Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)

        loadLyrics(intentSong)
    }

    private fun loadLyrics(song: Song) {
        showLoading()
        val durationSeconds = song.duration / 1000
        LyricsRepository.fetch(song.title, song.artist, durationSeconds, object : LyricsRepository.LyricsCallback {
            override fun onSuccess(result: LyricsResult) {
                when {
                    result.isInstrumental -> showMessage("Esta canción es instrumental")
                    !result.syncedLines.isNullOrEmpty() -> showSynced(result.syncedLines)
                    !result.plainLyrics.isNullOrBlank() -> showPlain(result.plainLyrics)
                    else -> showMessage("No se encontró letra para esta canción")
                }
            }

            override fun onError(message: String) {
                showMessage(message)
            }
        })
    }

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        tvMessage.visibility = View.GONE
        rvLyrics.visibility = View.GONE
    }

    private fun showMessage(message: String) {
        progressBar.visibility = View.GONE
        rvLyrics.visibility = View.GONE
        tvMessage.visibility = View.VISIBLE
        tvMessage.text = message
    }

    private fun showSynced(lines: List<LyricsLine>) {
        progressBar.visibility = View.GONE
        tvMessage.visibility = View.GONE
        rvLyrics.visibility = View.VISIBLE

        val adapter = LyricsLineAdapter(lines)
        syncedAdapter = adapter
        rvLyrics.adapter = adapter
    }

    private fun showPlain(text: String) {
        progressBar.visibility = View.GONE
        rvLyrics.visibility = View.VISIBLE

        val staticLines = text.lines()
            .filter { it.isNotBlank() }
            .map { LyricsLine(timeMs = -1, text = it) }
        rvLyrics.adapter = LyricsLineAdapter(staticLines)
        // Sin timestamps: no hay línea activa que resaltar.
    }

    /**
     * Pone la linea activa siempre pegada arriba del RecyclerView (nunca
     * centrada). Se pospone con post{} para que el smooth-scroll arranque
     * después de que notifyItemChanged (llamado justo antes, en
     * updateActiveLine) haya terminado su paso de layout. Si se llama de
     * forma síncrona, el cálculo usa el alto "viejo" de la línea (antes de
     * agrandarse por ser la activa) y el scroll termina pasándose de largo.
     *
     * Al quedar siempre pegada arriba, cuando ya no quedan más líneas
     * debajo (cerca del final de la canción) el RecyclerView no tiene más
     * contenido que subir: la letra se detiene ahí y el resto de la
     * pantalla se queda vacío, en vez de forzar ningún otro acomodo.
     */
    private fun scrollToLine(index: Int) {
        rvLyrics.post {
            val layoutManager = rvLyrics.layoutManager as? LinearLayoutManager ?: return@post
            val smoothScroller = object : LinearSmoothScroller(this) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_START
            }
            smoothScroller.targetPosition = index
            layoutManager.startSmoothScroll(smoothScroller)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(syncRunnable)
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
