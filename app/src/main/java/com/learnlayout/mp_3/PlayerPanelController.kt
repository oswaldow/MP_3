package com.learnlayout.mp_3

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

class PlayerPanelController(
    private val activity: AppCompatActivity,
    private val getMusicService: () -> MusicService?,
    private val playerPanel: FrameLayout,
    private val groupExpanded: View,
    private val groupMini: View,
    private val ivMiniAlbumArt: ImageView,
    private val tvMiniTitle: TextView,
    private val tvMiniArtist: TextView,
    private val btnMiniPlayPause: ImageButton,
    private val btnMiniPlayMode: ImageButton,
    private val circularMiniProgress: CircularProgressView,
    private val btnPanelBack: ImageButton,
    private val btnPanelQueue: ImageButton,
    private val btnPanelFavorite: ImageButton,
    private val btnPanelLyricsSync: ImageButton,
    private val ivPanelAlbumArt: ImageView,
    private val viewPanelArtBanner: View,
    private val tvPanelSongTitle: TextView,
    private val tvPanelArtist: TextView,
    private val sbPanelProgress: WaveformSeekBar,
    private val tvPanelCurrentTime: TextView,
    private val tvPanelTotalTime: TextView,
    private val btnPanelPrevious: ImageButton,
    private val btnPanelPlayPause: ImageButton,
    private val btnPanelNext: ImageButton,
    private val onExpanded: () -> Unit,
    private val onCollapsed: () -> Unit,
    private val onShowQueue: () -> Unit,
    private val onFavoriteToggled: () -> Unit,
    private val onAlbumArtLongPress: (Song) -> Unit,
    // Avisa cada vez que cambia la caratula (o se va a placeholder, bitmap
    // null) para que quien arme este controller pueda enterar a otras
    // vistas, p.ej. el banner del panel de letra (Material You).
    private val onAlbumArtChanged: (Bitmap?) -> Unit
) {

    private lateinit var behavior: BottomSheetBehavior<FrameLayout>

    private var isUserSeekingPanel: Boolean = false

    private val baseGroupMiniPaddingBottom: Int = groupMini.paddingBottom
    private val baseGroupExpandedPaddingBottom: Int = groupExpanded.paddingBottom

    // Padding original de cada placeholder (ic_music_note), leido antes de
    // tocarlo, para poder restaurarlo si una cancion no tiene caratula.
    private val miniAlbumArtBasePadding = intArrayOf(
        ivMiniAlbumArt.paddingLeft, ivMiniAlbumArt.paddingTop,
        ivMiniAlbumArt.paddingRight, ivMiniAlbumArt.paddingBottom
    )
    private val panelAlbumArtBasePadding = intArrayOf(
        ivPanelAlbumArt.paddingLeft, ivPanelAlbumArt.paddingTop,
        ivPanelAlbumArt.paddingRight, ivPanelAlbumArt.paddingBottom
    )

    // Cancion "vigente": si la respuesta de red llega tarde y para entonces
    // ya cambio la cancion, se descarta (evita pisar la caratula nueva con
    // la de una cancion anterior).
    private var currentArtSongId: Long? = null

    // Color de fondo de viewPanelArtBanner cuando no hay caratula (o mientras
    // se genera la paleta la primera vez). Es el mismo gris oscuro que ya
    // usaba el panel antes de este cambio, para que no haya salto visual.
    private val defaultBannerColor: Int =
        ContextCompat.getColor(activity, R.color.surface_dark)

    // Color de acento (Material You) para tintar play/pause, siguiente,
    // anterior, etc. Empieza en un gris neutro y se anima al color de la
    // caratula cada vez que cambia la cancion (ver applyControlsAccent).
    private val defaultAccentColor: Int =
        ContextCompat.getColor(activity, R.color.text_primary_light)
    private var currentAccentColor: Int = defaultAccentColor

    init {
        // Caratula "un poco redondeada": setImageBitmap() por si solo pinta
        // un rectangulo filoso encima del fondo con esquinas (bg_album_art),
        // asi que hay que recortar la vista misma.
        applyRoundedCorners(ivMiniAlbumArt, 6f)
        applyRoundedCorners(ivPanelAlbumArt, 10f)
    }

    private fun applyRoundedCorners(view: ImageView, radiusDp: Float) {
        val radiusPx = radiusDp * activity.resources.displayMetrics.density
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
    }

    val isReady: Boolean
        get() = ::behavior.isInitialized

    val isExpanded: Boolean
        get() = isReady && behavior.state == BottomSheetBehavior.STATE_EXPANDED

    val isVisible: Boolean
        get() = playerPanel.visibility == View.VISIBLE

    fun setup() {
        behavior = BottomSheetBehavior.from(playerPanel)
        behavior.isHideable = false
        behavior.skipCollapsed = false

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        groupMini.alpha = 0f
                        groupExpanded.alpha = 1f
                        groupMini.visibility = View.INVISIBLE
                        groupExpanded.visibility = View.VISIBLE
                        onExpanded()
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        groupMini.alpha = 1f
                        groupExpanded.alpha = 0f
                        groupMini.visibility = View.VISIBLE
                        groupExpanded.visibility = View.INVISIBLE
                        updatePeekHeight()
                        onCollapsed()
                    }
                    else -> {
                        groupMini.visibility = View.VISIBLE
                        groupExpanded.visibility = View.VISIBLE
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val progress = slideOffset.coerceIn(0f, 1f)
                groupMini.alpha = (1f - (progress / 0.5f)).coerceIn(0f, 1f)
                groupExpanded.alpha = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
            }
        })

        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        groupMini.alpha = 1f
        groupExpanded.alpha = 0f
        groupMini.visibility = View.VISIBLE
        groupExpanded.visibility = View.INVISIBLE

        groupMini.setOnClickListener {
            if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        btnPanelBack.setOnClickListener {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

        btnPanelQueue.setOnClickListener {
            onShowQueue()
        }

        btnPanelFavorite.setOnClickListener {
            toggleFavorite()
        }

        btnPanelLyricsSync.setOnClickListener {
            openLyricsSyncScreen()
        }

        ivPanelAlbumArt.setOnLongClickListener {
            val song = getMusicService()?.getCurrentSong()
            if (song != null) {
                onAlbumArtLongPress(song)
            }
            true
        }

        btnPanelPlayPause.setOnClickListener {
            getMusicService()?.togglePlayPause()
        }

        btnPanelPrevious.setOnClickListener {
            getMusicService()?.playPrevious()
        }

        btnPanelNext.setOnClickListener {
            getMusicService()?.playNext()
        }

        btnMiniPlayPause.setOnClickListener {
            getMusicService()?.togglePlayPause()
        }

        btnMiniPlayMode.setOnClickListener {
            val service = getMusicService() ?: return@setOnClickListener
            val newMode = service.cyclePlaybackMode()
            updateModeButtonIcon(newMode)

            val message = when (newMode) {
                MusicService.PlaybackMode.NORMAL -> "Reproduccion normal"
                MusicService.PlaybackMode.REPEAT_ONE -> "Repitiendo cancion actual"
                MusicService.PlaybackMode.SHUFFLE -> "Reproduccion aleatoria"
            }
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }

        sbPanelProgress.listener = object : WaveformSeekBar.OnWaveformSeekListener {
            override fun onProgressChanged(progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvPanelCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch() {
                isUserSeekingPanel = true
            }

            override fun onStopTrackingTouch(progress: Int) {
                isUserSeekingPanel = false
                getMusicService()?.seekTo(progress)
            }
        }
    }

    // ---------- Drag guard (usado por dispatchTouchEvent de la Activity) ----------

    fun setDraggable(draggable: Boolean) {
        if (isReady) {
            behavior.setDraggable(draggable)
        }
    }

    // ---------- Estado del panel ----------

    fun collapse() {
        if (isReady) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    fun expandWhenReady() {
        playerPanel.doOnLayout {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    fun updatePeekHeight() {
        groupMini.post {
            val height = groupMini.height
            if (isReady && height > 0 && height != behavior.peekHeight) {
                behavior.peekHeight = height
                playerPanel.requestLayout()
            }
        }
    }

    // ---------- Insets / edge-to-edge ----------

    fun applyBottomInset(systemBarsBottom: Int) {
        groupMini.setPadding(
            groupMini.paddingLeft,
            groupMini.paddingTop,
            groupMini.paddingRight,
            baseGroupMiniPaddingBottom + systemBarsBottom
        )

        groupExpanded.setPadding(
            groupExpanded.paddingLeft,
            groupExpanded.paddingTop,
            groupExpanded.paddingRight,
            baseGroupExpandedPaddingBottom + systemBarsBottom
        )
    }

    // ---------- Now playing ----------

    fun updateNowPlaying(song: Song, playing: Boolean) {
        playerPanel.visibility = View.VISIBLE

        tvMiniTitle.text = song.title
        tvMiniArtist.text = song.artist
        tvPanelSongTitle.text = song.title
        tvPanelArtist.text = song.artist
        sbPanelProgress.setWaveformSeed(song.id)

        btnMiniPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause_small else R.drawable.ic_play_small
        )
        btnPanelPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )

        updateFavoriteIcon(song.id)
        loadAlbumArt(song)
        updatePeekHeight()
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        btnMiniPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause_small else R.drawable.ic_play_small
        )
        btnPanelPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    // Llamado desde el poller de progreso de la Activity (cada 500ms),
    // solo mientras isVisible es true.
    fun updateProgress(currentMs: Int, totalMs: Int) {
        val service = getMusicService()

        circularMiniProgress.setProgress(currentMs, totalMs)
        btnMiniPlayPause.setImageResource(
            if (service?.isPlaying() == true) R.drawable.ic_pause_small else R.drawable.ic_play_small
        )

        if (!isUserSeekingPanel) {
            sbPanelProgress.max = if (totalMs > 0) totalMs else 0
            sbPanelProgress.progress = currentMs
        }
        tvPanelCurrentTime.text = formatTime(currentMs.toLong())
        tvPanelTotalTime.text = formatTime(totalMs.toLong())
        btnPanelPlayPause.setImageResource(
            if (service?.isPlaying() == true) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    fun updateModeButtonIcon(mode: MusicService.PlaybackMode) {
        when (mode) {
            MusicService.PlaybackMode.NORMAL -> {
                btnMiniPlayMode.setImageResource(R.drawable.ic_repeat)
                btnMiniPlayMode.setBackgroundResource(R.drawable.bg_icon_button_circle)
            }
            MusicService.PlaybackMode.REPEAT_ONE -> {
                btnMiniPlayMode.setImageResource(R.drawable.ic_repeat_one)
                btnMiniPlayMode.setBackgroundResource(R.drawable.btn_primary_round)
            }
            MusicService.PlaybackMode.SHUFFLE -> {
                btnMiniPlayMode.setImageResource(R.drawable.ic_shuffle)
                btnMiniPlayMode.setBackgroundResource(R.drawable.btn_primary_round)
            }
        }
    }

    // ---------- Caratula del album (iTunes / Deezer) ----------

    private fun loadAlbumArt(song: Song) {
        currentArtSongId = song.id

        showAlbumArtPlaceholder()

        AlbumArtRepository.loadCover(activity, song, object : AlbumArtRepository.Callback {
            override fun onCoverReady(bitmap: Bitmap) {
                // Si mientras se descargaba ya cambio la cancion, se descarta.
                if (currentArtSongId != song.id) return
                applyAlbumArtBitmap(ivMiniAlbumArt, bitmap)
                applyAlbumArtBitmap(ivPanelAlbumArt, bitmap)
                onAlbumArtChanged(bitmap)
                // Banner estilo Material You: color extraido de la caratula.
                PlayerPaletteTheme.applyFromBitmap(bitmap, viewPanelArtBanner, defaultBannerColor)
                // Color de acento para los controles (play/pause, siguiente,
                // anterior, modo). Mismo espiritu de Material You pero sin
                // oscurecer, para que los iconos se vean saturados.
                PlayerPaletteTheme.applyAccentFromBitmap(
                    bitmap, defaultAccentColor, currentAccentColor
                ) { color ->
                    currentAccentColor = color
                    applyControlsAccent(color)
                }
            }
        })
    }

    /**
     * Aplica manualmente una caratula elegida por el usuario (long-press
     * sobre ivPanelAlbumArt -> selector de opciones) para [song], sin pasar
     * de nuevo por la busqueda automatica. Se llama despues de que
     * [AlbumArtRepository.applyOverride] ya guardo el bitmap en cache.
     */
    fun applyAlbumArtOverride(song: Song, bitmap: Bitmap) {
        if (currentArtSongId != song.id) return
        applyAlbumArtBitmap(ivMiniAlbumArt, bitmap)
        applyAlbumArtBitmap(ivPanelAlbumArt, bitmap)
        onAlbumArtChanged(bitmap)
        PlayerPaletteTheme.applyFromBitmap(bitmap, viewPanelArtBanner, defaultBannerColor)
        PlayerPaletteTheme.applyAccentFromBitmap(
            bitmap, defaultAccentColor, currentAccentColor
        ) { color ->
            currentAccentColor = color
            applyControlsAccent(color)
        }
    }

    private fun applyAlbumArtBitmap(iv: ImageView, bitmap: Bitmap) {
        iv.setPadding(0, 0, 0, 0)
        iv.imageTintList = null
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(bitmap)
    }

    private fun showAlbumArtPlaceholder() {
        applyPlaceholder(ivMiniAlbumArt, miniAlbumArtBasePadding)
        applyPlaceholder(ivPanelAlbumArt, panelAlbumArtBasePadding)
        onAlbumArtChanged(null)
        PlayerPaletteTheme.applyFallback(viewPanelArtBanner, defaultBannerColor)
        PlayerPaletteTheme.applyAccentFallback(defaultAccentColor, currentAccentColor) { color ->
            currentAccentColor = color
            applyControlsAccent(color)
        }
    }

    /**
     * Tinta los controles de reproduccion con [color] (extraido de la
     * caratula o el fallback neutro). El boton grande de play/pause tinta
     * su fondo circular (antes @color/white fijo) y su icono se recalcula
     * a blanco o negro segun cual contraste mejor. El resto de botones
     * (anterior, siguiente, modo, mini play/pause) solo tintan el icono,
     * ya que su fondo es transparente.
     */
    private fun applyControlsAccent(color: Int) {
        val onColor = PlayerPaletteTheme.onColorFor(color)
        val accentTint = ColorStateList.valueOf(color)
        val onColorTint = ColorStateList.valueOf(onColor)

        btnPanelPlayPause.backgroundTintList = accentTint
        btnPanelPlayPause.imageTintList = onColorTint

        btnPanelPrevious.imageTintList = accentTint
        btnPanelNext.imageTintList = accentTint
        btnMiniPlayMode.imageTintList = accentTint
        btnMiniPlayPause.imageTintList = accentTint
    }

    private fun applyPlaceholder(iv: ImageView, basePadding: IntArray) {
        iv.setPadding(basePadding[0], basePadding[1], basePadding[2], basePadding[3])
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setImageResource(R.drawable.ic_music_note)
        iv.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(activity, R.color.spotify_gray)
        )
    }

    private fun updateFavoriteIcon(songId: Long?) {
        val isFav = songId != null && PlaylistRepository.isFavorite(activity, songId)
        btnPanelFavorite.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

    private fun toggleFavorite() {
        val song = getMusicService()?.getCurrentSong() ?: return
        val isNowFavorite = PlaylistRepository.toggleFavorite(activity, song.id)
        btnPanelFavorite.setImageResource(
            if (isNowFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        onFavoriteToggled()
    }

    private fun openLyricsSyncScreen() {
        val currentSong = getMusicService()?.getCurrentSong() ?: run {
            Toast.makeText(activity, "No hay cancion reproduciendose", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(activity, LyricsActivity::class.java)
        intent.putExtra("song", currentSong)
        activity.startActivity(intent)
        activity.overridePendingTransition(R.anim.activity_slide_up_in, R.anim.activity_stay)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}