package com.learnlayout.mp_3

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

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
    private val btnPanelSleepTimer: ImageButton,
    private val btnPanelQueue: ImageButton,
    private val btnPanelFavorite: ImageButton,
    private val btnPanelLyricsSync: ImageButton,
    private val ivPanelAlbumArt: ImageView,
    private val audioSpectrumView: AudioSpectrumView,
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
    // Opcion "Buscar caratula y letra" del menu que aparece al mantener
    // presionada la caratula (ver showAlbumArtLongPressMenu). Es la misma
    // funcion que ya existia antes de agregar el menu.
    private val onAlbumArtLongPress: (Song) -> Unit,
    // Opcion "Editar nombre y artista" del mismo menu.
    private val onEditSongMetadata: (Song) -> Unit = {},
    // Avisa cada vez que cambia la caratula (o se va a placeholder, bitmap
    // null) para que quien arme este controller pueda enterar a otras
    // vistas, p.ej. el banner del panel de letra (Material You).
    private val onAlbumArtChanged: (Bitmap?) -> Unit,
    private val onAccentColorChanged: (Int) -> Unit = {}
) {

    private companion object {
        const val SLEEP_TIMER_INACTIVE_ALPHA = 140
    }

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

    private var isUserSeekingPanel = false

    private val miniAlbumArtBasePadding = intArrayOf(
        ivMiniAlbumArt.paddingLeft, ivMiniAlbumArt.paddingTop,
        ivMiniAlbumArt.paddingRight, ivMiniAlbumArt.paddingBottom
    )
    private val panelAlbumArtBasePadding = intArrayOf(
        ivPanelAlbumArt.paddingLeft, ivPanelAlbumArt.paddingTop,
        ivPanelAlbumArt.paddingRight, ivPanelAlbumArt.paddingBottom
    )

    private val animationController by lazy {
        PlayerPanelAnimationController(
            activity = activity,
            playerPanel = playerPanel,
            groupExpanded = groupExpanded,
            groupMini = groupMini,
            audioSpectrumView = audioSpectrumView,
            btnPanelBack = btnPanelBack,
            btnPanelSleepTimer = btnPanelSleepTimer,
            onExpanded = onExpanded,
            onCollapsed = onCollapsed
        )
    }

    fun getAccentColor(): Int = currentAccentColor

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
        get() = animationController.isReady

    val isExpanded: Boolean
        get() = animationController.isExpanded

    val isVisible: Boolean
        get() = playerPanel.visibility == View.VISIBLE

    fun setup() {
        animationController.setup()

        btnPanelSleepTimer.setOnClickListener {
            showSleepTimerMenu()
        }
        updateSleepTimerIcon()

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
                showAlbumArtLongPressMenu(song)
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
        animationController.setDraggable(draggable)
    }

    // ---------- Estado del panel ----------

    fun collapse() {
        animationController.collapse()
    }

    // Se usa para abrir el panel "en frio": recien se toco una cancion en
    // la lista, se reconecto al servicio, o se volvio de otra pantalla con
    // expandPlayerOnResume. En estos casos el panel NUNCA estuvo visible
    // en su estado mini de forma legitima, asi que usa coldExpand() en vez
    // de smoothExpand() para no pintar ese estado ni un solo frame.
    fun expandWhenReady() {
        animationController.expandWhenReady()
    }

    fun updatePeekHeight() {
        animationController.updatePeekHeight()
    }

    // ---------- Insets / edge-to-edge ----------

    // btnPanelBack y btnPanelSleepTimer estan constreñidos al top de
    // groupExpanded, que no recibe el inset de status bar de forma
    // automatica (playerPanel es hermano de rootLayout, no hijo). Sin esto
    // quedan pegados arriba del todo -incluso debajo del notch en equipos
    // con camara recortada-.
    fun applyTopInset(systemBarsTop: Int) {
        animationController.applyTopInset(systemBarsTop)
    }

    fun applyBottomInset(systemBarsBottom: Int) {
        animationController.applyBottomInset(systemBarsBottom)
    }

    // ---------- Now playing ----------

    // Actualiza a la vez el icono play/pause del mini player y el del
    // panel expandido: los tres callers (updateNowPlaying,
    // updatePlaybackState, updateProgress) necesitaban exactamente la
    // misma pareja de resourceId segun isPlaying.
    private fun applyPlayPauseIcons(isPlaying: Boolean) {
        btnMiniPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause_small else R.drawable.ic_play_small)
        btnPanelPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    }

    fun updateNowPlaying(song: Song, playing: Boolean) {
        playerPanel.visibility = View.VISIBLE

        tvMiniTitle.text = song.title
        tvMiniArtist.text = song.artist
        tvPanelSongTitle.text = song.title
        tvPanelArtist.text = song.artist
        sbPanelProgress.setWaveformSeed(song.id)

        applyPlayPauseIcons(playing)

        updateFavoriteIcon(song.id)
        loadAlbumArt(song)
        updatePeekHeight()
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        applyPlayPauseIcons(isPlaying)
    }

    // Llamado desde el poller de progreso de la Activity (cada 500ms),
    // solo mientras isVisible es true.
    fun updateProgress(currentMs: Int, totalMs: Int) {
        val isPlaying = getMusicService()?.isPlaying() == true

        circularMiniProgress.setProgress(currentMs, totalMs)
        applyPlayPauseIcons(isPlaying)

        if (!isUserSeekingPanel) {
            sbPanelProgress.max = if (totalMs > 0) totalMs else 0
            sbPanelProgress.progress = currentMs
        }
        tvPanelCurrentTime.text = formatTime(currentMs.toLong())
        tvPanelTotalTime.text = formatTime(totalMs.toLong())
        updateSleepTimerIcon()
    }

    fun updateModeButtonIcon(mode: MusicService.PlaybackMode) {
        when (mode) {
            MusicService.PlaybackMode.NORMAL -> {
                btnMiniPlayMode.setImageResource(R.drawable.ic_repeat)
            }
            MusicService.PlaybackMode.REPEAT_ONE -> {
                btnMiniPlayMode.setImageResource(R.drawable.ic_repeat_one)
            }
            MusicService.PlaybackMode.SHUFFLE -> {
                btnMiniPlayMode.setImageResource(R.drawable.ic_shuffle)
            }
        }
        btnMiniPlayMode.background = null
    }

    // ---------- Sleep timer ----------

    private fun showSleepTimerMenu() {
        val service = getMusicService() ?: return

        val popupView = activity.layoutInflater.inflate(R.layout.popup_sleep_timer, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.elevation = 16f

        val tv5: TextView = popupView.findViewById(R.id.tvSleepTimer5)
        val tv15: TextView = popupView.findViewById(R.id.tvSleepTimer15)
        val tv30: TextView = popupView.findViewById(R.id.tvSleepTimer30)
        val tv60: TextView = popupView.findViewById(R.id.tvSleepTimer60)
        val tvEndOfSong: TextView = popupView.findViewById(R.id.tvSleepTimerEndOfSong)
        val dividerCancel: View = popupView.findViewById(R.id.dividerSleepTimerCancel)
        val tvCancel: TextView = popupView.findViewById(R.id.tvSleepTimerCancel)

        // "Cancelar temporizador" solo aparece si ya hay uno corriendo. Si el
        // modo activo es "al terminar la cancion", se resalta esa opcion con
        // el mismo morado de acento que usa el resto de la app para marcar
        // un estado seleccionado (ver bg_chip_eq_preset_selected).
        val hasActiveTimer = service.isSleepTimerActive()
        dividerCancel.visibility = if (hasActiveTimer) View.VISIBLE else View.GONE
        tvCancel.visibility = if (hasActiveTimer) View.VISIBLE else View.GONE
        tvEndOfSong.setTextColor(
            ContextCompat.getColor(
                activity,
                if (service.isSleepTimerEndOfSongActive()) R.color.spotify_green else R.color.text_primary_light
            )
        )

        tv5.setOnClickListener { applySleepTimerMinutes(5); popupWindow.dismiss() }
        tv15.setOnClickListener { applySleepTimerMinutes(15); popupWindow.dismiss() }
        tv30.setOnClickListener { applySleepTimerMinutes(30); popupWindow.dismiss() }
        tv60.setOnClickListener { applySleepTimerMinutes(60); popupWindow.dismiss() }
        tvEndOfSong.setOnClickListener {
            getMusicService()?.setSleepTimerEndOfSong()
            updateSleepTimerIcon()
            Toast.makeText(activity, "Se pausara al terminar la cancion", Toast.LENGTH_SHORT).show()
            popupWindow.dismiss()
        }
        tvCancel.setOnClickListener {
            getMusicService()?.cancelSleepTimer()
            updateSleepTimerIcon()
            Toast.makeText(activity, "Temporizador cancelado", Toast.LENGTH_SHORT).show()
            popupWindow.dismiss()
        }

        popupWindow.showAsDropDown(btnPanelSleepTimer, -180, 12)
    }

    private fun applySleepTimerMinutes(minutes: Int) {
        getMusicService()?.setSleepTimerMinutes(minutes)
        updateSleepTimerIcon()
        Toast.makeText(activity, "Se pausara en $minutes min", Toast.LENGTH_SHORT).show()
    }

    // ---------- Menu de la caratula (mantener presionada) ----------

    /**
     * Menu con dos opciones al mantener presionada la caratula del panel:
     * 1. Editar nombre y artista (reusa el mismo dialogo que ya existe en
     *    la lista principal, ver PlaylistDialogs.showEditSongMetadataDialog).
     * 2. Buscar caratula y letra (la funcion que antes se disparaba
     *    directo con el long-press, sin pasar por ningun menu).
     */
    private fun showAlbumArtLongPressMenu(song: Song) {
        val popupView = activity.layoutInflater.inflate(R.layout.popup_album_art_menu, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.elevation = 16f

        val tvEdit: TextView = popupView.findViewById(R.id.tvAlbumArtMenuEdit)
        val tvSearch: TextView = popupView.findViewById(R.id.tvAlbumArtMenuSearch)

        tvEdit.setOnClickListener {
            onEditSongMetadata(song)
            popupWindow.dismiss()
        }

        tvSearch.setOnClickListener {
            onAlbumArtLongPress(song)
            popupWindow.dismiss()
        }

        popupWindow.showAsDropDown(ivPanelAlbumArt, 0, 12)
    }

    // Version atenuada (mismo tono, menos opaca) del acento actual cuando
    // no hay timer activo, o el acento completo cuando si lo hay. Antes
    // usaba un gris fijo para el estado inactivo, lo que lo dejaba fuera
    // de la paleta Material You que ya siguen el resto de los controles.
    // Se vuelve a llamar en cada tick de updateProgress() para que el
    // icono se apague solo cuando el timer por minutos termina y pausa
    // la musica.
    private fun updateSleepTimerIcon() {
        val active = getMusicService()?.isSleepTimerActive() == true
        val color = if (active) {
            currentAccentColor
        } else {
            ColorUtils.setAlphaComponent(currentAccentColor, SLEEP_TIMER_INACTIVE_ALPHA)
        }
        btnPanelSleepTimer.imageTintList = ColorStateList.valueOf(color)
    }

    // ---------- Caratula del album (iTunes / Deezer) ----------

    private fun loadAlbumArt(song: Song) {
        currentArtSongId = song.id

        val callback = object : AlbumArtRepository.Callback {
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
        }

        // Si la caratula ya esta en memoria (misma cancion ya vista antes
        // en esta sesion), se aplica directo sin pasar por el placeholder:
        // evita el parpadeo icono->caratula en el mini player y el panel.
        val cached = AlbumArtRepository.getCachedCover(song)
        if (cached != null) {
            callback.onCoverReady(cached)
            return
        }

        showAlbumArtPlaceholder()
        // Solo memoria/disco: escuchar musica ya no dispara busqueda de
        // caratula por red (ver AlbumArtRepository.loadCoverCacheOnly).
        // La busqueda en red ahora solo ocurre al mantener presionada la
        // caratula (ver onAlbumArtLongPress) o desde la descarga masiva
        // en Configuracion.
        AlbumArtRepository.loadCoverCacheOnly(activity, song, callback)
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

    private fun applyControlsAccent(color: Int) {
        val onColor = PlayerPaletteTheme.onColorFor(color)
        val accentTint = ColorStateList.valueOf(color)
        val onColorTint = ColorStateList.valueOf(onColor)

        btnPanelPlayPause.backgroundTintList = accentTint
        btnPanelPlayPause.imageTintList = onColorTint

        btnPanelBack.imageTintList = accentTint
        btnPanelPrevious.imageTintList = accentTint
        btnPanelNext.imageTintList = accentTint
        btnPanelQueue.imageTintList = accentTint
        btnMiniPlayMode.imageTintList = accentTint
        btnMiniPlayPause.imageTintList = accentTint

        // El icono del sleep timer no se tiñe aca: su color depende ademas
        // de si el timer esta activo (ver updateSleepTimerIcon), pero
        // igual necesita re-tintarse cada vez que cambia el acento.
        updateSleepTimerIcon()

        onAccentColorChanged(color)
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