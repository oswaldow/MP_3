package com.learnlayout.mp_3

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
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
    // Contenedor del banner de letra (lyricsCoordinator, hermano de
    // groupExpanded dentro de playerPanel). Se reenvia sin mas a
    // PlayerPanelAnimationController, que es quien le sincroniza el alpha
    // con groupExpanded durante la expansion/colapso del panel.
    private val lyricsCoordinator: View,
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
    private val btnPanelAddToPlaylist: ImageButton,
    private val ivPanelAlbumArt: ImageView,
    private val albumArtTransitionOverlay: FrameLayout,
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
    // Boton "Agregar a playlist" de la fila superior del panel expandido
    // (btnPanelAddToPlaylist). Reusa PlaylistDialogs.showAddToPlaylistDialog,
    // igual que el resto de los puntos de entrada a esa funcion.
    private val onAddToPlaylist: (Song) -> Unit = {},
    // Avisa cada vez que cambia la caratula (o se va a placeholder, bitmap
    // null) para que quien arme este controller pueda enterar a otras
    // vistas, p.ej. el banner del panel de letra (Material You).
    private val onAlbumArtChanged: (Bitmap?) -> Unit,
    private val onAccentColorChanged: (Int) -> Unit = {}
) {

    private companion object {
        const val SLEEP_TIMER_INACTIVE_ALPHA = 140

        // FIX: cuanto se espera antes de mostrar el placeholder
        // (ic_music_note) al cambiar de cancion. AlbumArtRepository.
        // loadCoverCacheOnly() relee el archivo de audio completo en cada
        // llamada (por diseno, ver su comentario), asi que aunque no salga
        // a red, tarda un instante perceptible. Antes ese instante se
        // pintaba siempre con el placeholder, causando un parpadeo
        // icono->caratula en cada "siguiente"/"anterior". Ahora el
        // placeholder solo aparece si la carga tarda MAS de este tiempo;
        // en el caso normal (carga rapida) nunca llega a mostrarse.
        const val ALBUM_ART_PLACEHOLDER_DELAY_MS = 150L

        // Duracion del deslizamiento de la caratula (ivPanelAlbumArt) al
        // pasar de cancion. tvPanelSongTitle/tvPanelArtist usan un fundido
        // cruzado de la mitad de este tiempo (fade out + fade in).
        const val ALBUM_ART_SLIDE_DURATION_MS = 260L
        const val PANEL_TEXT_CROSSFADE_DURATION_MS = ALBUM_ART_SLIDE_DURATION_MS / 2

    }

    // Sentido del deslizamiento de la caratula: cancion siguiente = la
    // actual sale por la izquierda y la nueva entra por la derecha;
    // cancion anterior, al reves.
    private enum class ArtSlideDirection { NEXT, PREVIOUS }

    // Cancion "vigente": si la respuesta de red llega tarde y para entonces
    // ya cambio la cancion, se descarta (evita pisar la caratula nueva con
    // la de una cancion anterior).
    private var currentArtSongId: Long? = null

    // FIX: handler + runnable pendiente para el retraso del placeholder
    // (ver ALBUM_ART_PLACEHOLDER_DELAY_MS). Se cancela apenas la caratula
    // real esta lista o apenas se pide otra cancion nueva, para que nunca
    // se dispare un placeholder "viejo" encima de una caratula ya aplicada.
    private val albumArtHandler = Handler(Looper.getMainLooper())
    private var pendingPlaceholderRunnable: Runnable? = null

    // FIX: direccion pendiente del proximo cambio de cancion, si vino de
    // los botones siguiente/anterior (ver requestNextSong/
    // requestPreviousSong). La consume updateNowPlaying() una unica vez
    // por cancion; si el cambio vino de otro lado (elegir de la cola,
    // autoplay al terminar la cancion, etc.) queda en null y no hay
    // deslizamiento ni fundido.
    private var pendingArtSlideDirection: ArtSlideDirection? = null
    private var albumArtSlideAnimator: ValueAnimator? = null
    private var outgoingAlbumArtView: ImageView? = null

    // Color de fondo de viewPanelArtBanner cuando no hay caratula (o mientras
    // se genera la paleta la primera vez). Es el mismo gris oscuro que ya
    // usaba el panel antes de este cambio, para que no haya salto visual.
    private val defaultBannerColor: Int =
        ContextCompat.getColor(activity, R.color.surface_dark)

    private val defaultAccentColor: Int =
        ContextCompat.getColor(activity, R.color.text_primary_light)
    private var currentAccentColor: Int = defaultAccentColor

    // Ultimo color de banner calculado (ver PlayerPaletteTheme.applyFromBitmap
    // / applyFallback). Se usa en applyControlsAccent() para chequear que
    // los iconos "sueltos" (sin su propio circulo de fondo) tengan
    // contraste real contra el banner, en vez de asumir a ciegas que el
    // acento siempre se va a distinguir (ver PlayerPaletteTheme.iconColorFor).
    private var currentBannerColor: Int = defaultBannerColor

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
            lyricsCoordinator = lyricsCoordinator,
            audioSpectrumView = audioSpectrumView,
            btnPanelBack = btnPanelBack,
            btnPanelSleepTimer = btnPanelSleepTimer,
            ivMiniAlbumArt = ivMiniAlbumArt,
            ivPanelAlbumArt = ivPanelAlbumArt,
            albumArtTransitionOverlay = albumArtTransitionOverlay,
            getAccentColor = { currentAccentColor },
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

        ButtonTapFillAnimator.setOnClickListener(btnPanelSleepTimer, { currentAccentColor }) {
            showSleepTimerMenu()
        }
        updateSleepTimerIcon()

        ButtonTapFillAnimator.setOnClickListener(btnPanelSleepTimer, { currentAccentColor }) {
            showSleepTimerMenu()
        }
        updateSleepTimerIcon()

        ButtonTapFillAnimator.setOnClickListener(btnPanelQueue, { currentAccentColor }) {
            onShowQueue()
        }

        ButtonTapFillAnimator.setOnClickListener(btnPanelFavorite, { currentAccentColor }) {
            toggleFavorite()
        }

        ButtonTapFillAnimator.setOnClickListener(btnPanelLyricsSync, { currentAccentColor }) {
            openLyricsSyncScreen()
        }

        ButtonTapFillAnimator.setOnClickListener(btnPanelAddToPlaylist, { currentAccentColor }) {
            val song = getMusicService()?.getCurrentSong()
            if (song != null) {
                onAddToPlaylist(song)
            }
        }

        ivPanelAlbumArt.setOnLongClickListener {
            val song = getMusicService()?.getCurrentSong()
            if (song != null) {
                showAlbumArtLongPressMenu(song)
            }
            true
        }

        // btnPanelPlayPause queda igual: ya esta permanentemente relleno
        // con el acento (ver applyControlsAccent), asi que el flash no
        // aportaria nada visualmente.
        btnPanelPlayPause.setOnClickListener {
            getMusicService()?.togglePlayPause()
        }

        ButtonTapFillAnimator.setOnClickListener(btnPanelPrevious, { currentAccentColor }) {
            requestPreviousSong()
        }

        ButtonTapFillAnimator.setOnClickListener(btnPanelNext, { currentAccentColor }) {
            requestNextSong()
        }

        ButtonTapFillAnimator.setOnClickListener(btnMiniPlayPause, { currentAccentColor }) {
            getMusicService()?.togglePlayPause()
        }

        ButtonTapFillAnimator.setOnClickListener(btnMiniPlayMode, { currentAccentColor }) {
            val service = getMusicService()
            if (service != null) {
                val newMode = service.cyclePlaybackMode()
                updateModeButtonIcon(newMode)

                val message = when (newMode) {
                    MusicService.PlaybackMode.NORMAL -> "Reproduccion normal"
                    MusicService.PlaybackMode.REPEAT_ONE -> "Repitiendo cancion actual"
                    MusicService.PlaybackMode.SHUFFLE -> "Reproduccion aleatoria"
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
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
    // Se usa para abrir el panel "en frio": recien se toco una cancion en
    // la lista, se reconecto al servicio, o se volvio de otra pantalla con
    // expandPlayerOnResume. En estos casos el panel NUNCA estuvo visible
    // en su estado mini de forma legitima, asi que usa coldExpand() en vez
    // de smoothExpand() para no pintar ese estado ni un solo frame.
    fun expandWhenReady() {
        animationController.expandWhenReady()
    }

    // Se usa cuando el mini reproductor YA estaba visible en pantalla antes
    // de este toque (por ejemplo, se toco una caratula de Home mientras
    // sonaba otra cancion). A diferencia de expandWhenReady(), aca si hay
    // un estado mini legitimo del que partir: usa la animacion corta con
    // crossfade de groupMini/groupExpanded y caratula compartida.
    fun smoothExpand() {
        animationController.smoothExpand()
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

    // FIX: marca la direccion del proximo cambio de cancion y recien
    // despues le pide a MusicService que avance/retroceda. updateNowPlaying()
    // (disparada por el listener de MusicService cuando la cancion
    // efectivamente cambia) consume esta marca para decidir si anima la
    // caratula/texto o los cambia directo como antes.
    private fun requestNextSong() {
        pendingArtSlideDirection = ArtSlideDirection.NEXT
        getMusicService()?.playNext()
    }

    private fun requestPreviousSong() {
        pendingArtSlideDirection = ArtSlideDirection.PREVIOUS
        getMusicService()?.playPrevious()
    }

    fun updateNowPlaying(song: Song, playing: Boolean, autoAdvance: Boolean = false) {
        playerPanel.visibility = View.VISIBLE

        // Se consume una sola vez aca: si el cambio de cancion vino del
        // boton siguiente/anterior (o de un swipe sobre la caratula) usa
        // esa direccion explicita. Si no, pero la cancion termino sola
        // (autoAdvance = true, ver MusicService.PlaybackListener.
        // onSongChanged), se anima igual que "siguiente": el avance
        // automatico siempre es hacia adelante en la cola, nunca hacia
        // atras. Si tampoco es autoAdvance (se eligio de la cola o de una
        // lista), queda en null y todo se comporta como antes: sin
        // deslizamiento ni fundido.
        val slideDirection = pendingArtSlideDirection
            ?: if (autoAdvance) ArtSlideDirection.NEXT else null
        pendingArtSlideDirection = null

        tvMiniTitle.text = song.title
        tvMiniArtist.text = song.artist
        if (slideDirection != null) {
            crossfadeText(tvPanelSongTitle, song.title)
            crossfadeText(tvPanelArtist, song.artist)
        } else {
            tvPanelSongTitle.text = song.title
            tvPanelArtist.text = song.artist
        }
        sbPanelProgress.setWaveformSeed(song.id)

        applyPlayPauseIcons(playing)

        updateFavoriteIcon(song.id)
        loadAlbumArt(song, slideDirection)
        updatePeekHeight()
    }

    // Fundido cruzado (fade out -> cambia el texto -> fade in) para
    // tvPanelSongTitle/tvPanelArtist cuando el cambio de cancion vino de
    // siguiente/anterior o del swipe. Reemplaza al cambio instantaneo que
    // se sigue usando en el resto de los casos (ver updateNowPlaying).
    private fun crossfadeText(view: TextView, newText: String) {
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(PANEL_TEXT_CROSSFADE_DURATION_MS)
            .withEndAction {
                view.text = newText
                view.animate()
                    .alpha(1f)
                    .setDuration(PANEL_TEXT_CROSSFADE_DURATION_MS)
                    .start()
            }
            .start()
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

    // ---------- Caratula del album (iTunes / Deezer) ----------

    private fun loadAlbumArt(song: Song, slideDirection: ArtSlideDirection? = null) {
        currentArtSongId = song.id
        // FIX: se cancela cualquier placeholder que hubiera quedado
        // pendiente de la cancion anterior antes de programar uno nuevo
        // (ver schedulePlaceholder), asi nunca aparece de golpe encima de
        // la caratula/mini player de la cancion que se acaba de pedir.
        cancelPendingPlaceholder()
        // FIX: marca esta cancion como "la que esta sonando" para que su
        // caratula quede protegida de ser expulsada del cache mientras el
        // usuario navega otras listas (ver AlbumArtRepository.
        // pinCurrentlyPlaying). Se llama sin bitmap todavia: se completa
        // solo cuando llegue el resultado (cache o loadCoverCacheOnly).
        AlbumArtRepository.pinCurrentlyPlaying(song.id)

        val callback = object : AlbumArtRepository.Callback {
            override fun onCoverReady(bitmap: Bitmap) {
                // Si mientras se descargaba ya cambio la cancion, se descarta.
                if (currentArtSongId != song.id) return
                // FIX: la caratula llego (a tiempo o no): si el placeholder
                // retrasado todavia no se disparo, se cancela para que
                // nunca llegue a pintarse.
                cancelPendingPlaceholder()
                AlbumArtRepository.pinCurrentlyPlaying(song.id, bitmap)
                applyAlbumArtBitmap(ivMiniAlbumArt, bitmap)
                // FIX: si el cambio vino de siguiente/anterior/swipe, la
                // caratula del panel se desliza en vez de cambiar de
                // golpe (ver slideInAlbumArt). El mini player nunca se
                // anima, solo el panel grande.
                if (slideDirection != null) {
                    slideInAlbumArt(bitmap, slideDirection)
                } else {
                    applyAlbumArtBitmap(ivPanelAlbumArt, bitmap)
                }
                onAlbumArtChanged(bitmap)
                // Banner estilo Material You: color extraido de la caratula.
                PlayerPaletteTheme.applyFromBitmap(bitmap, defaultBannerColor, { color -> currentBannerColor = color }, viewPanelArtBanner)
                // Color de acento para los controles (play/pause, siguiente,
                // anterior, modo). Mismo espiritu de Material You pero sin
                // oscurecer, para que los iconos se vean saturados.
                PlayerPaletteTheme.applyAccentFromBitmap(
                    bitmap, defaultAccentColor, currentAccentColor
                ) { color ->
                    currentAccentColor = color
                    applyControlsAccent(color)
                    AppAccentColor.update(color)
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

        // FIX: antes se llamaba a showAlbumArtPlaceholder() aca mismo, al
        // instante, y se lo reemplazaba recien cuando terminaba
        // loadCoverCacheOnly(). Como esa funcion relee el archivo de audio
        // completo en cada llamada (por diseno), el resultado normalmente
        // llega en un instante muy breve pero no nulo, y ese instante se
        // veia como un parpadeo del icono de nota musical en cada cambio
        // de cancion. Ahora se programa el placeholder con un pequeno
        // retraso (ver schedulePlaceholder): si la carga real es mas
        // rapida que ese retraso -el caso normal-, el placeholder se
        // cancela en onCoverReady() antes de llegar a dibujarse.
        schedulePlaceholder(song.id)
        // Solo memoria/disco: escuchar musica ya no dispara busqueda de
        // caratula por red (ver AlbumArtRepository.loadCoverCacheOnly).
        // La busqueda en red ahora solo ocurre al mantener presionada la
        // caratula (ver onAlbumArtLongPress) o desde la descarga masiva
        // en Configuracion.
        AlbumArtRepository.loadCoverCacheOnly(activity, song, callback)
    }

    // FIX: hace que ivPanelAlbumArt "entre" mostrando [newBitmap] desde el
    // lado que indica [direction], mientras una copia con la imagen
    // anterior sale por el lado contrario. Si la vista todavia no tiene
    // tamano (primerisima vez que se muestra el panel) no hay nada que
    // deslizar: se aplica directo, igual que antes de este cambio.
    private fun slideInAlbumArt(newBitmap: Bitmap, direction: ArtSlideDirection) {
        val parent = ivPanelAlbumArt.parent as? ViewGroup
        val width = ivPanelAlbumArt.width
        if (parent == null || width <= 0) {
            applyAlbumArtBitmap(ivPanelAlbumArt, newBitmap)
            return
        }

        // Si ya habia un deslizamiento en curso (cambios de cancion muy
        // rapidos, p.ej. tocando siguiente varias veces seguidas), se
        // corta limpio antes de arrancar el nuevo.
        cancelAlbumArtSlide()

        // Copia "volante" con la imagen VIEJA, superpuesta exactamente
        // sobre ivPanelAlbumArt: es la que se ve salir de pantalla.
        val outgoing = ImageView(activity).apply {
            scaleType = ivPanelAlbumArt.scaleType
            setImageDrawable(ivPanelAlbumArt.drawable)
            clipToOutline = ivPanelAlbumArt.clipToOutline
            outlineProvider = ivPanelAlbumArt.outlineProvider
        }
        parent.addView(
            outgoing,
            parent.indexOfChild(ivPanelAlbumArt) + 1,
            ViewGroup.LayoutParams(width, ivPanelAlbumArt.height)
        )
        outgoing.x = ivPanelAlbumArt.x
        outgoing.y = ivPanelAlbumArt.y
        outgoingAlbumArtView = outgoing

        // "Siguiente": la actual sale por la izquierda (outSign -1) y la
        // nueva entra por la derecha (inSign +1). "Anterior": al reves.
        val outSign = if (direction == ArtSlideDirection.NEXT) -1f else 1f
        val inSign = -outSign

        applyAlbumArtBitmap(ivPanelAlbumArt, newBitmap)
        ivPanelAlbumArt.translationX = width * inSign

        albumArtSlideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ALBUM_ART_SLIDE_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                outgoing.translationX = width * outSign * p
                ivPanelAlbumArt.translationX = width * inSign * (1f - p)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    parent.removeView(outgoing)
                    if (outgoingAlbumArtView === outgoing) outgoingAlbumArtView = null
                }
            })
            start()
        }
    }

    // Corta en seco cualquier deslizamiento de caratula en curso: saca la
    // copia volante y deja ivPanelAlbumArt en su posicion normal. Se llama
    // antes de arrancar un deslizamiento nuevo y antes de aplicar una
    // caratula manual (applyAlbumArtOverride), para que nunca quede a
    // mitad de camino.
    private fun cancelAlbumArtSlide() {
        albumArtSlideAnimator?.cancel()
        albumArtSlideAnimator = null
        outgoingAlbumArtView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        outgoingAlbumArtView = null
        ivPanelAlbumArt.translationX = 0f
    }

    // FIX: agenda mostrar el placeholder (ic_music_note) para [songId]
    // dentro de ALBUM_ART_PLACEHOLDER_DELAY_MS. Si para entonces ya llego
    // la caratula real (o se pidio otra cancion), el runnable se cancela
    // en cancelPendingPlaceholder() y nunca llega a ejecutarse.
    private fun schedulePlaceholder(songId: Long) {
        val runnable = Runnable {
            // Doble chequeo: ademas de haberse cancelado explicitamente,
            // por las dudas de que el runnable ya estuviera en la cola de
            // mensajes cuando cambio la cancion, se confirma que sigue
            // siendo la cancion vigente antes de mostrar el icono.
            if (currentArtSongId == songId) {
                showAlbumArtPlaceholder()
            }
        }
        pendingPlaceholderRunnable = runnable
        albumArtHandler.postDelayed(runnable, ALBUM_ART_PLACEHOLDER_DELAY_MS)
    }

    private fun cancelPendingPlaceholder() {
        pendingPlaceholderRunnable?.let { albumArtHandler.removeCallbacks(it) }
        pendingPlaceholderRunnable = null
    }

    /**
     * Aplica manualmente una caratula elegida por el usuario (long-press
     * sobre ivPanelAlbumArt -> selector de opciones) para [song], sin pasar
     * de nuevo por la busqueda automatica. Se llama despues de que
     * [AlbumArtRepository.applyOverride] ya guardo el bitmap en cache.
     */
    fun applyAlbumArtOverride(song: Song, bitmap: Bitmap) {
        if (currentArtSongId != song.id) return
        cancelPendingPlaceholder()
        cancelAlbumArtSlide()
        AlbumArtRepository.pinCurrentlyPlaying(song.id, bitmap)
        applyAlbumArtBitmap(ivMiniAlbumArt, bitmap)
        applyAlbumArtBitmap(ivPanelAlbumArt, bitmap)
        onAlbumArtChanged(bitmap)
        PlayerPaletteTheme.applyFromBitmap(bitmap, defaultBannerColor, { color -> currentBannerColor = color }, viewPanelArtBanner)
        PlayerPaletteTheme.applyAccentFromBitmap(
            bitmap, defaultAccentColor, currentAccentColor
        ) { color ->
            currentAccentColor = color
            applyControlsAccent(color)
            AppAccentColor.update(color)
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
        PlayerPaletteTheme.applyFallback(defaultBannerColor, { color -> currentBannerColor = color }, viewPanelArtBanner)
        PlayerPaletteTheme.applyAccentFallback(defaultAccentColor, currentAccentColor) { color ->
            currentAccentColor = color
            applyControlsAccent(color)
        }
        // Sin caratula no hay color que extraer: el resto de la app (tab
        // activo, botones de Home, chips del ecualizador) debe volver a su
        // fallback morado fijo, no quedarse pegado al ultimo color de la
        // ultima cancion con caratula.
        AppAccentColor.reset()
    }

    private fun applyControlsAccent(color: Int) {
        val onColor = PlayerPaletteTheme.onColorFor(color)
        val accentTint = ColorStateList.valueOf(color)
        val onColorTint = ColorStateList.valueOf(onColor)

        btnPanelPlayPause.backgroundTintList = accentTint
        btnPanelPlayPause.imageTintList = onColorTint

        // Botones "sueltos" (sin su propio circulo de fondo, se dibujan
        // directo sobre el banner/mini player): a diferencia de
        // btnPanelPlayPause, aca no hay un color de fondo propio que
        // garantice contraste, asi que si el acento sale demasiado
        // parecido al banner real (caratulas muy monocromaticas) se cae
        // a blanco/negro puro en vez de quedar practicamente invisibles.
        val looseIconTint = ColorStateList.valueOf(
            PlayerPaletteTheme.iconColorFor(currentBannerColor, color)
        )
        btnPanelBack.imageTintList = looseIconTint
        btnPanelPrevious.imageTintList = looseIconTint
        btnPanelNext.imageTintList = looseIconTint
        btnPanelQueue.imageTintList = looseIconTint
        btnPanelAddToPlaylist.imageTintList = looseIconTint
        btnMiniPlayMode.imageTintList = looseIconTint
        btnMiniPlayPause.imageTintList = looseIconTint

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