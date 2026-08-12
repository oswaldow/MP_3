package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Home de la biblioteca musical.
 *
 * No modifica la reproduccion: solo presenta accesos y carouseles usando los
 * repositorios que ya existen en la app.
 */
class HomeController(
    private val context: Context,
    private val root: View,
    private val getAllSongs: () -> List<Song>,
    private val getCurrentSong: () -> Song?,
    private val isPlaying: () -> Boolean,
    private val onPlaySong: (Song) -> Unit,
    private val onOpenSongs: () -> Unit
) {
    private val tvWelcome: TextView = root.findViewById(R.id.tvHomeWelcome)
    private val tvSummary: TextView = root.findViewById(R.id.tvHomeSummary)
    private val heroContainer: View = root.findViewById(R.id.homeHeroCard)
    private val ivHeroArt: ImageView = root.findViewById(R.id.ivHomeHeroArt)
    private val tvHeroEyebrow: TextView = root.findViewById(R.id.tvHomeHeroEyebrow)
    private val tvHeroTitle: TextView = root.findViewById(R.id.tvHomeHeroTitle)
    private val tvHeroArtist: TextView = root.findViewById(R.id.tvHomeHeroArtist)
    private val btnHeroPlay: ImageButton = root.findViewById(R.id.btnHomeHeroPlay)

    private val recentSection: View = root.findViewById(R.id.homeRecentSection)
    private val recentContainer: LinearLayout = root.findViewById(R.id.homeRecentContainer)
    private val mostSection: View = root.findViewById(R.id.homeMostSection)
    private val mostContainer: LinearLayout = root.findViewById(R.id.homeMostContainer)
    private val addedSection: View = root.findViewById(R.id.homeAddedSection)
    private val addedContainer: LinearLayout = root.findViewById(R.id.homeAddedContainer)

    private val tvFavoritesCount: TextView = root.findViewById(R.id.tvHomeFavoritesCount)
    private val tvRecentCount: TextView = root.findViewById(R.id.tvHomeRecentCount)
    private val tvMostCount: TextView = root.findViewById(R.id.tvHomeMostCount)

    private var heroSong: Song? = null

    fun refresh() {
        val songs = getAllSongs()
        val current = getCurrentSong()
        val recentIds = PlayCountRepository.getRecentlyPlayedSongIds(context, 12)
        val mostIds = PlayCountRepository.getMostPlayedSongIds(context, 12)
        val favorites = PlaylistRepository.getPlaylistById(
            context,
            PlaylistRepository.FAVORITES_PLAYLIST_ID
        )?.songIds.orEmpty()

        tvWelcome.text = greeting()
        tvSummary.text = when {
            songs.isEmpty() -> "Agrega música a tu biblioteca para empezar"
            songs.size == 1 -> "1 canción en tu biblioteca"
            else -> "${songs.size} canciones en tu biblioteca"
        }

        tvFavoritesCount.text = favorites.size.toString()
        tvRecentCount.text = recentIds.size.toString()
        tvMostCount.text = mostIds.size.toString()

        val byId = songs.associateBy { it.id }
        val recentSongs = recentIds.mapNotNull { byId[it] }
        val mostSongs = mostIds.mapNotNull { byId[it] }
        val addedSongs = songs.sortedByDescending { it.dateAdded }.take(12)

        configureHero(current ?: recentSongs.firstOrNull() ?: addedSongs.firstOrNull())
        populateSongRow(recentContainer, recentSongs.take(8))
        recentSection.visibility = if (recentSongs.isNotEmpty()) View.VISIBLE else View.GONE

        populateSongRow(mostContainer, mostSongs.take(8))
        mostSection.visibility = if (mostSongs.isNotEmpty()) View.VISIBLE else View.GONE

        populateSongRow(addedContainer, addedSongs.take(8))
        addedSection.visibility = if (addedSongs.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun configureHero(song: Song?) {
        heroSong = song
        if (song == null) {
            heroContainer.visibility = View.VISIBLE
            tvHeroEyebrow.text = "TU BIBLIOTECA"
            tvHeroTitle.text = "Empieza a escuchar"
            tvHeroArtist.text = "Toca para ver tus canciones"
            ivHeroArt.setImageResource(R.drawable.ic_music_note)
            ivHeroArt.setPadding(dp(32), dp(32), dp(32), dp(32))
            btnHeroPlay.setImageResource(R.drawable.ic_queue_music)
            heroContainer.setOnClickListener { onOpenSongs() }
            btnHeroPlay.setOnClickListener { onOpenSongs() }
            return
        }

        tvHeroEyebrow.text = if (getCurrentSong()?.id == song.id) "SONANDO AHORA" else "CONTINUAR ESCUCHANDO"
        tvHeroTitle.text = song.title
        tvHeroArtist.text = song.artist
        btnHeroPlay.setImageResource(if (getCurrentSong()?.id == song.id && isPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)

        heroContainer.setOnClickListener { onPlaySong(song) }
        btnHeroPlay.setOnClickListener { onPlaySong(song) }
        loadCover(song, ivHeroArt, 180)
    }

    private fun populateSongRow(container: LinearLayout, songs: List<Song>) {
        container.removeAllViews()
        songs.forEach { song ->
            val item = LayoutInflater.from(context).inflate(R.layout.item_home_song, container, false)
            val iv = item.findViewById<ImageView>(R.id.ivHomeSongArt)
            val title = item.findViewById<TextView>(R.id.tvHomeSongTitle)
            val artist = item.findViewById<TextView>(R.id.tvHomeSongArtist)
            title.text = song.title
            artist.text = song.artist
            item.setOnClickListener { onPlaySong(song) }
            loadCover(song, iv, 140)
            container.addView(item)
        }
    }

    private fun loadCover(song: Song, imageView: ImageView, targetDp: Int) {
        imageView.setImageResource(R.drawable.ic_music_note)
        imageView.setPadding(dp(26), dp(26), dp(26), dp(26))
        imageView.imageTintList = ContextCompat.getColorStateList(context, R.color.spotify_gray)

        val cached = AlbumArtRepository.getCachedCover(song)
        if (cached != null) {
            applyBitmap(imageView, cached)
            return
        }

        AlbumArtRepository.loadCover(context, song, object : AlbumArtRepository.Callback {
            override fun onCoverReady(bitmap: Bitmap) {
                applyBitmap(imageView, bitmap)
            }
        })
    }

    private fun applyBitmap(imageView: ImageView, bitmap: Bitmap) {
        imageView.setPadding(0, 0, 0, 0)
        imageView.imageTintList = null
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.setImageBitmap(bitmap)
    }

    private fun greeting(): String {
        return when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Buenos días"
            in 12..18 -> "Buenas tardes"
            else -> "Buenas noches"
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}