package com.learnlayout.mp_3

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
    private val onAlbumArtLongPress: (Song) -> Unit,
    // Avisa cada vez que cambia la caratula (o se va a placeholder, bitmap
    // null) para que quien arme este controller pueda enterar a otras
    // vistas, p.ej. el banner del panel de letra (Material You).
    private val onAlbumArtChanged: (Bitmap?) -> Unit,
    private val onAccentColorChanged: (Int) -> Unit = {}
) {

    private companion object {
        // DEBUG: filtrar en Logcat con  adb logcat -s MP3_PANEL
        // (temporal, para diagnosticar el "asomo" del mini player al
        // abrir el reproductor -ver coldExpand()-. Se puede borrar una
        // vez resuelto.)
        private const val TAG_PANEL = "MP3_PANEL"

        // Opacidad (0-255) del icono del sleep timer cuando esta inactivo.
        // Es una version atenuada del acento actual en vez de un gris fijo,
        // para que igual se sienta parte de la paleta Material You.
        const val SLEEP_TIMER_INACTIVE_ALPHA = 140

        // Duracion e interpolador de la apertura animada a mano del panel
        // (ver smoothExpand). BottomSheetBehavior no expone ninguna API
        // publica para controlar la duracion de su propia animacion de
        // "settle", y esta resulta bastante rapida/abrupta cuando se
        // dispara programaticamente (no arrastrando con el dedo). Por eso
        // se anima el desplazamiento a mano con estos valores, mas suaves.
        const val EXPAND_ANIM_DURATION_MS = 320L
    }

    private lateinit var behavior: BottomSheetBehavior<FrameLayout>

    // True mientras se realiza la apertura "en frio". Durante esta fase el
    // BottomSheetBehavior ya esta en STATE_EXPANDED, pero la posicion visual
    // se anima manualmente mediante translationY. Asi no dejamos que el
    // callback de STATE_EXPANDED arranque efectos secundarios antes de que
    // termine la animacion visual.
    private var coldExpandInProgress = false
    private var expandAnimator: ValueAnimator? = null

    private var isUserSeekingPanel: Boolean = false

    private val baseGroupMiniPaddingBottom: Int = groupMini.paddingBottom
    private val baseGroupExpandedPaddingBottom: Int = groupExpanded.paddingBottom

    // Margen superior original de los botones del header del panel expandido
    // (btnPanelBack / btnPanelSleepTimer), leido del XML antes de que
    // applyTopInset() le sume el alto de la status bar / notch en tiempo de
    // ejecucion. Sin esto quedan pegados arriba del todo, porque playerPanel
    // es hermano de rootLayout (no hijo) y nunca recibe ese inset por su cuenta.
    private val baseBtnPanelBackMarginTop: Int =
        (btnPanelBack.layoutParams as ConstraintLayout.LayoutParams).topMargin
    private val baseBtnPanelSleepTimerMarginTop: Int =
        (btnPanelSleepTimer.layoutParams as ConstraintLayout.LayoutParams).topMargin

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
                Log.d(TAG_PANEL, "onStateChanged: newState=${stateName(newState)} " +
                        "panelHeight=${playerPanel.height} peekHeight=${behavior.peekHeight} " +
                        "translationY=${playerPanel.translationY} thread=${Thread.currentThread().name}")
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        groupMini.alpha = 0f
                        groupExpanded.alpha = 1f
                        groupMini.visibility = View.INVISIBLE
                        groupExpanded.visibility = View.VISIBLE

                        // En una apertura en frio el behavior se pone en
                        // EXPANDED antes de que el panel vuelva a ser visible.
                        // No arranquemos aun el resto de la UI: la animacion
                        // visual de translationY sigue en curso.
                        if (coldExpandInProgress) {
                            return
                        }

                        audioSpectrumView.start()
                        onExpanded()
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        groupMini.alpha = 1f
                        groupExpanded.alpha = 0f
                        groupMini.visibility = View.VISIBLE
                        groupExpanded.visibility = View.INVISIBLE
                        updatePeekHeight()
                        audioSpectrumView.stop()
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
        audioSpectrumView.stop()

        groupMini.setOnClickListener {
            if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                smoothExpand()
            }
        }

        btnPanelBack.setOnClickListener {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

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
        expandAnimator?.cancel()
        coldExpandInProgress = false
        playerPanel.translationY = 0f
        if (isReady) {
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    // Se usa para abrir el panel "en frio": recien se toco una cancion en
    // la lista, se reconecto al servicio, o se volvio de otra pantalla con
    // expandPlayerOnResume. En estos casos el panel NUNCA estuvo visible
    // en su estado mini de forma legitima, asi que usa coldExpand() en vez
    // de smoothExpand() para no pintar ese estado ni un solo frame.
    fun expandWhenReady() {
        Log.d(TAG_PANEL, "expandWhenReady: llamado. panelVisibility=${visibilityName(playerPanel.visibility)} " +
                "panelHeight=${playerPanel.height} isLaidOut=${playerPanel.isLaidOut} " +
                "isLayoutRequested=${playerPanel.isLayoutRequested} thread=${Thread.currentThread().name}")

        if (!isReady || behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            Log.d(TAG_PANEL, "expandWhenReady: return temprano (no ready o ya expandido)")
            return
        }

        // IMPORTANTE: no esperamos al doOnLayout para cambiar el estado del
        // BottomSheetBehavior. Mientras el panel sigue GONE lo llevamos a
        // EXPANDED. De esa forma, la primera pasada de layout ya calcula la
        // posicion real expandida (top=0) y nunca existe un layout intermedio
        // en COLLAPSED que pueda disparar STATE_SETTLING.
        expandAnimator?.cancel()
        coldExpandInProgress = true
        behavior.isDraggable = false

        // Dejamos preparado el contenido expandido antes de que el panel
        // vuelva a entrar en el arbol visible.
        groupMini.alpha = 0f
        groupMini.visibility = View.INVISIBLE
        groupExpanded.alpha = 1f
        groupExpanded.visibility = View.VISIBLE

        // El panel puede llegar aqui ya visible (por ejemplo al volver de otra
        // Activity). Lo ocultamos durante este cambio de estado para que el
        // setter de STATE_EXPANDED no tenga que hacer un settle desde el layout
        // colapsado que ya estaba en pantalla. La traslacion inicial queda
        // fuera de la pantalla antes de volver a ponerlo VISIBLE.
        val offscreenOffset = maxOf(
            playerPanel.height,
            playerPanel.rootView.height,
            activity.resources.displayMetrics.heightPixels
        ).toFloat()
        playerPanel.translationY = offscreenOffset
        playerPanel.visibility = View.GONE

        // ESTE es el punto clave del arreglo: el behavior cambia a EXPANDED
        // mientras el panel sigue GONE. No debe producir ningun movimiento
        // visual ni un settle nativo.
        behavior.state = BottomSheetBehavior.STATE_EXPANDED

        // Volvemos a mostrarlo. La primera pasada de layout ya ocurrira con
        // STATE_EXPANDED y con translationY fuera de pantalla. Al terminar el
        // layout podemos conocer el alto real y arrancar la unica animacion
        // visual, de alto -> 0.
        playerPanel.visibility = View.VISIBLE
        playerPanel.doOnLayout {
            Log.d(TAG_PANEL, "expandWhenReady: doOnLayout disparado. panelHeight=${playerPanel.height} " +
                    "behaviorState=${stateName(behavior.state)} " +
                    "groupMini.visibility=${visibilityName(groupMini.visibility)} " +
                    "groupExpanded.visibility=${visibilityName(groupExpanded.visibility)} " +
                    "thread=${Thread.currentThread().name}")
            coldExpand()
        }
    }

    // Expande el panel animando el desplazamiento a mano en vez de dejar
    // que BottomSheetBehavior use su propia animacion de "settle" (rapida
    // y sin API publica para ajustar duracion/interpolador). Se desactiva
    // el drag mientras dura la animacion para que no se pise con un gesto
    // del usuario, y al terminar se deja el behavior en STATE_EXPANDED
    // (que ya no anima nada, porque el panel ya esta en su posicion final).
    //
    // Se usa SOLO cuando el usuario toca el mini player ya visible en
    // pantalla (ver groupMini.setOnClickListener en setup()): ahi si tiene
    // sentido animar desde la posicion "peek" (mini) hasta la expandida,
    // porque el mini player que se ve durante el arranque de la animacion
    // es el mismo que el usuario acaba de tocar.
    private fun smoothExpand() {
        Log.d(TAG_PANEL, "smoothExpand: llamado. isReady=$isReady state=${if (isReady) stateName(behavior.state) else "N/A"} " +
                "panelHeight=${playerPanel.height} peekHeight=${if (isReady) behavior.peekHeight else -1}")

        if (!isReady || behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            Log.d(TAG_PANEL, "smoothExpand: return temprano (no ready o ya expandido)")
            return
        }

        val panelHeight = playerPanel.height
        val startOffset = (panelHeight - behavior.peekHeight).toFloat()

        Log.d(TAG_PANEL, "smoothExpand: startOffset=$startOffset")

        if (startOffset <= 0f) {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            return
        }

        expandAnimator?.cancel()
        coldExpandInProgress = false
        behavior.isDraggable = false

        // El mini-player ya esta visible porque el usuario acaba de tocar el
        // banner. Para evitar que BottomSheetBehavior haga su propio SETTLING,
        // ocultamos temporalmente el panel, cambiamos su estado a EXPANDED y
        // despues lo mostramos ya anclado en la posicion expandida.
        //
        // La animacion que ve el usuario sera exclusivamente translationY:
        // startOffset -> 0.
        groupMini.visibility = View.VISIBLE
        groupMini.alpha = 1f
        groupExpanded.visibility = View.VISIBLE
        groupExpanded.alpha = 0f

        playerPanel.translationY = startOffset
        playerPanel.visibility = View.GONE

        // CLAVE: el behavior cambia a EXPANDED mientras el panel sigue GONE.
        // Asi BottomSheetBehavior no puede iniciar el settle desde COLLAPSED.
        behavior.state = BottomSheetBehavior.STATE_EXPANDED

        playerPanel.visibility = View.VISIBLE

        expandAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = EXPAND_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator(1.4f)

            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                playerPanel.translationY = value

                val progress = 1f - (value / startOffset).coerceIn(0f, 1f)

                // El mini-player desaparece gradualmente mientras entra el
                // contenido expandido.
                groupMini.alpha = (1f - (progress / 0.5f)).coerceIn(0f, 1f)

                // El contenido expandido entra despues de que el panel haya
                // recorrido una parte del trayecto.
                groupExpanded.alpha = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)

                if (progress > 0.4f) {
                    groupExpanded.visibility = View.VISIBLE
                }
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (expandAnimator !== animation) return

                    playerPanel.translationY = 0f
                    groupMini.alpha = 0f
                    groupMini.visibility = View.INVISIBLE
                    groupExpanded.alpha = 1f
                    groupExpanded.visibility = View.VISIBLE

                    behavior.isDraggable = true
                    expandAnimator = null

                    // NO volver a asignar STATE_EXPANDED aqui.
                    // Ya se establecio antes de hacer visible el panel.
                    Log.d(TAG_PANEL, "smoothExpand: animacion terminada sin settle nativo")
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (expandAnimator !== animation) return

                    playerPanel.translationY = 0f
                    behavior.isDraggable = true
                    expandAnimator = null
                }
            })

            start()
        }
    }

    // Version "en frio" de smoothExpand(): en vez de arrancar desde la
    // posicion "peek" (que se alcanza a pintar como el mini player un
    // frame antes de animar, el "asomo" que se ve al tocar una cancion
    // desde la lista), arranca con el panel entero corrido fuera de la
    // pantalla (translationY = alto total) y con el contenido expandido
    // ya puesto (groupExpanded visible, groupMini invisible) DESDE ANTES
    // de la primera pasada de layout/dibujo. Asi el mini player nunca
    // llega a pintarse ni un solo frame: se ve un unico slide-up continuo
    // que ya trae la pantalla completa del reproductor.
    private fun coldExpand() {
        Log.d(TAG_PANEL, "coldExpand: llamado. isReady=$isReady state=${if (isReady) stateName(behavior.state) else "N/A"} " +
                "panelHeight=${playerPanel.height} panelVisibility=${visibilityName(playerPanel.visibility)} " +
                "groupMini.visibility=${visibilityName(groupMini.visibility)} " +
                "groupExpanded.visibility=${visibilityName(groupExpanded.visibility)} " +
                "thread=${Thread.currentThread().name}")

        if (!isReady || !coldExpandInProgress) {
            Log.d(TAG_PANEL, "coldExpand: return temprano (no ready o no hay apertura en frio pendiente)")
            return
        }

        if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
            Log.d(TAG_PANEL, "coldExpand: el behavior no quedo EXPANDED; se cancela para no disparar un settle")
            coldExpandInProgress = false
            behavior.isDraggable = true
            playerPanel.translationY = 0f
            return
        }

        val startOffset = playerPanel.height.toFloat()
        Log.d(TAG_PANEL, "coldExpand: layout expandido listo. startOffset=$startOffset -> arrancando ValueAnimator")

        if (startOffset <= 0f) {
            playerPanel.translationY = 0f
            coldExpandInProgress = false
            behavior.isDraggable = true
            audioSpectrumView.start()
            onExpanded()
            return
        }

        // Ajustamos al alto real justo despues del layout y antes del primer
        // frame visible. Por eso nunca se ve el panel ya expandido antes de
        // comenzar la animacion.
        playerPanel.translationY = startOffset

        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = EXPAND_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                playerPanel.translationY = anim.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (expandAnimator !== this@apply) return
                    playerPanel.translationY = 0f
                    expandAnimator = null
                    coldExpandInProgress = false
                    behavior.isDraggable = true

                    // STATE_EXPANDED ya estaba puesto desde antes del layout,
                    // por lo que aqui NO se vuelve a tocar behavior.state y no
                    // hay ninguna segunda animacion nativa de BottomSheet.
                    audioSpectrumView.start()
                    onExpanded()
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (expandAnimator !== this@apply) return
                    expandAnimator = null
                    coldExpandInProgress = false
                    playerPanel.translationY = 0f
                    behavior.isDraggable = true
                }
            })
            start()
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

    // btnPanelBack y btnPanelSleepTimer estan constreñidos al top de
    // groupExpanded, que no recibe el inset de status bar de forma
    // automatica (playerPanel es hermano de rootLayout, no hijo). Sin esto
    // quedan pegados arriba del todo -incluso debajo del notch en equipos
    // con camara recortada-.
    fun applyTopInset(systemBarsTop: Int) {
        setTopMargin(btnPanelBack, baseBtnPanelBackMarginTop + systemBarsTop)
        setTopMargin(btnPanelSleepTimer, baseBtnPanelSleepTimerMarginTop + systemBarsTop)
    }

    private fun setTopMargin(view: View, marginPx: Int) {
        val params = view.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (params.topMargin == marginPx) return
        params.topMargin = marginPx
        view.layoutParams = params
    }

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
        Log.d(TAG_PANEL, "updateNowPlaying: song='${song.title}' playing=$playing " +
                "panelVisibility(antes)=${visibilityName(playerPanel.visibility)} " +
                "panelHeight(antes)=${playerPanel.height} thread=${Thread.currentThread().name}")
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
        AlbumArtRepository.loadCover(activity, song, callback)
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

    private fun stateName(state: Int): String = when (state) {
        BottomSheetBehavior.STATE_EXPANDED -> "EXPANDED"
        BottomSheetBehavior.STATE_COLLAPSED -> "COLLAPSED"
        BottomSheetBehavior.STATE_DRAGGING -> "DRAGGING"
        BottomSheetBehavior.STATE_SETTLING -> "SETTLING"
        BottomSheetBehavior.STATE_HIDDEN -> "HIDDEN"
        BottomSheetBehavior.STATE_HALF_EXPANDED -> "HALF_EXPANDED"
        else -> "UNKNOWN($state)"
    }

    private fun visibilityName(visibility: Int): String = when (visibility) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "UNKNOWN($visibility)"
    }
}