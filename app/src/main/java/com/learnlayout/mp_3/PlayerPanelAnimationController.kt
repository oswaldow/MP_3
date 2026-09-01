package com.learnlayout.mp_3

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * Encapsula exclusivamente el comportamiento/animaciones del BottomSheet del
 * reproductor. PlayerPanelController queda encargado del contenido y de los
 * controles, mientras esta clase maneja estados, insets y transiciones.
 *
 * Ademas de expandir/colapsar el panel, esta clase es responsable de la
 * transicion "shared element" de la caratula (ver [SharedAlbumArtTransition]):
 * mientras el panel se desliza entre mini y expandido -ya sea arrastrando
 * con el dedo o con las animaciones programaticas de [smoothExpand]/
 * [collapse]-, la caratula del mini reproductor "vuela" hasta la posicion y
 * tamano de la caratula del panel expandido (y viceversa al colapsar).
 */
class PlayerPanelAnimationController(
    private val activity: AppCompatActivity,
    private val playerPanel: FrameLayout,
    private val groupExpanded: View,
    private val groupMini: View,
    // Contenedor del banner de letra (lyricsCoordinator), hermano de
    // groupExpanded dentro de playerPanel. Se sincroniza aqui con el
    // mismo alpha/progress que groupExpanded para que ambos aparezcan
    // juntos durante smoothExpand()/onSlide() y no se vea el banner de
    // letra ya solido mientras el resto del panel todavia esta
    // transparente (ver hilo sobre "huecos" al expandir el panel).
    private val lyricsCoordinator: View,
    private val audioSpectrumView: AudioSpectrumView,
    private val btnPanelBack: View,
    private val btnPanelSleepTimer: View,
    private val ivMiniAlbumArt: ImageView,
    private val ivPanelAlbumArt: ImageView,
    private val albumArtTransitionOverlay: FrameLayout,
    private val onExpanded: () -> Unit,
    private val onCollapsed: () -> Unit,

    private val getAccentColor: () -> Int
) {
    companion object {
        private const val TAG = "MP3_PANEL"
        private const val EXPAND_ANIM_DURATION_MS = 320L

        // Mismos radios que PlayerPanelController.applyRoundedCorners() usa
        // para ivMiniAlbumArt / ivPanelAlbumArt: deben coincidir para que la
        // vista "volante" no pegue un salto de esquinas al empezar/terminar.
        private const val MINI_ART_CORNER_RADIUS_DP = 6f
        private const val PANEL_ART_CORNER_RADIUS_DP = 10f
    }

    private lateinit var behavior: BottomSheetBehavior<FrameLayout>
    private var coldExpandInProgress = false
    private var expandAnimator: ValueAnimator? = null

    private val sharedAlbumArt = SharedAlbumArtTransition(albumArtTransitionOverlay)
    private val density = activity.resources.displayMetrics.density
    private val miniArtCornerRadiusPx = MINI_ART_CORNER_RADIUS_DP * density
    private val panelArtCornerRadiusPx = PANEL_ART_CORNER_RADIUS_DP * density

    private val baseGroupMiniPaddingBottom = groupMini.paddingBottom
    private val baseGroupExpandedPaddingBottom = groupExpanded.paddingBottom
    private val baseBtnPanelBackMarginTop =
        (btnPanelBack.layoutParams as ConstraintLayout.LayoutParams).topMargin
    private val baseBtnPanelSleepTimerMarginTop =
        (btnPanelSleepTimer.layoutParams as ConstraintLayout.LayoutParams).topMargin

    val isReady: Boolean
        get() = ::behavior.isInitialized

    val isExpanded: Boolean
        get() = isReady && behavior.state == BottomSheetBehavior.STATE_EXPANDED

    fun setup(onMiniClicked: () -> Unit = { smoothExpand() }) {
        behavior = BottomSheetBehavior.from(playerPanel)
        behavior.isHideable = false
        behavior.skipCollapsed = false

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        groupMini.alpha = 0f
                        groupExpanded.alpha = 1f
                        lyricsCoordinator.alpha = 1f
                        groupMini.visibility = View.INVISIBLE
                        groupExpanded.visibility = View.VISIBLE
                        lyricsCoordinator.visibility = View.VISIBLE
                        endSharedAlbumArt()

                        if (coldExpandInProgress) return

                        audioSpectrumView.start()
                        onExpanded()
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        groupMini.alpha = 1f
                        groupExpanded.alpha = 0f
                        lyricsCoordinator.alpha = 0f
                        groupMini.visibility = View.VISIBLE
                        groupExpanded.visibility = View.INVISIBLE
                        lyricsCoordinator.visibility = View.INVISIBLE
                        endSharedAlbumArt()
                        updatePeekHeight()
                        audioSpectrumView.stop()
                        onCollapsed()
                    }
                    else -> {
                        groupMini.visibility = View.VISIBLE
                        groupExpanded.visibility = View.VISIBLE
                        lyricsCoordinator.visibility = View.VISIBLE
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val progress = slideOffset.coerceIn(0f, 1f)
                groupMini.alpha = (1f - (progress / 0.5f)).coerceIn(0f, 1f)
                val expandedAlpha = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
                groupExpanded.alpha = expandedAlpha
                // El banner de letra comparte exactamente el mismo alpha que
                // groupExpanded (mismo progress, mismo umbral) para que suba
                // "pegado" al resto del panel expandido en vez de aparecer
                // de golpe mientras lo demas sigue transparente.
                lyricsCoordinator.alpha = expandedAlpha
                // Esto cubre tanto el arrastre manual del panel (el dedo del
                // usuario) como el "settle" por defecto de BottomSheetBehavior
                // al colapsar con el boton de atras: en ambos casos la
                // caratula vuela en sincronia con el propio deslizamiento.
                updateSharedAlbumArt(progress)
            }
        })

        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        groupMini.alpha = 1f
        groupExpanded.alpha = 0f
        lyricsCoordinator.alpha = 0f
        groupMini.visibility = View.VISIBLE
        groupExpanded.visibility = View.INVISIBLE
        lyricsCoordinator.visibility = View.INVISIBLE
        audioSpectrumView.stop()

        groupMini.setOnClickListener { onMiniClicked() }
        ButtonTapFillAnimator.setOnClickListener(btnPanelBack, getAccentColor) { collapse() }
    }

    fun setDraggable(draggable: Boolean) {
        if (isReady) behavior.setDraggable(draggable)
    }

    fun collapse() {
        expandAnimator?.cancel()
        coldExpandInProgress = false
        playerPanel.translationY = 0f
        if (isReady) {
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    fun expandWhenReady() {
        if (!isReady || behavior.state == BottomSheetBehavior.STATE_EXPANDED) return

        expandAnimator?.cancel()
        coldExpandInProgress = true
        behavior.isDraggable = false

        // Apertura "en frio" (p.ej. la app recien arranca con una cancion en
        // curso): no habia mini reproductor visible antes de esto, asi que
        // no hay un origen valido para la caratula volante. Se deja sin
        // shared element, solo el deslizamiento vertical de siempre.
        groupMini.alpha = 0f
        groupMini.visibility = View.INVISIBLE
        groupExpanded.alpha = 1f
        groupExpanded.visibility = View.VISIBLE
        lyricsCoordinator.alpha = 1f
        lyricsCoordinator.visibility = View.VISIBLE

        val offscreenOffset = maxOf(
            playerPanel.height,
            playerPanel.rootView.height,
            activity.resources.displayMetrics.heightPixels
        ).toFloat()
        playerPanel.translationY = offscreenOffset
        playerPanel.visibility = View.GONE
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        playerPanel.visibility = View.VISIBLE
        playerPanel.doOnLayoutCompat { coldExpand() }
    }

    fun smoothExpand() {
        if (!isReady || behavior.state == BottomSheetBehavior.STATE_EXPANDED) return

        val panelHeight = playerPanel.height
        val startOffset = (panelHeight - behavior.peekHeight).toFloat()
        if (startOffset <= 0f) {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            return
        }

        expandAnimator?.cancel()
        coldExpandInProgress = false
        behavior.isDraggable = false

        groupMini.visibility = View.VISIBLE
        groupMini.alpha = 1f
        groupExpanded.visibility = View.VISIBLE
        groupExpanded.alpha = 0f
        // Ya arrancamos con el banner de letra visible (a alpha 0, igual que
        // groupExpanded) para que ambos vayan encendiendose juntos frame a
        // frame en vez de que el banner aparezca de golpe con un toggle de
        // visibilidad aparte.
        lyricsCoordinator.visibility = View.VISIBLE
        lyricsCoordinator.alpha = 0f

        playerPanel.translationY = startOffset
        playerPanel.visibility = View.GONE
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        playerPanel.visibility = View.VISIBLE

        expandAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = EXPAND_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                playerPanel.translationY = value
                val progress = 1f - (value / startOffset).coerceIn(0f, 1f)
                groupMini.alpha = (1f - (progress / 0.5f)).coerceIn(0f, 1f)
                val expandedAlpha = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
                groupExpanded.alpha = expandedAlpha
                // Mismo alpha, mismo frame: el banner de letra sube "pegado"
                // al resto del panel expandido, sin desfase.
                lyricsCoordinator.alpha = expandedAlpha
                if (progress > 0.4f) groupExpanded.visibility = View.VISIBLE
                updateSharedAlbumArt(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (expandAnimator !== animation) return
                    playerPanel.translationY = 0f
                    groupMini.alpha = 0f
                    groupMini.visibility = View.INVISIBLE
                    groupExpanded.alpha = 1f
                    groupExpanded.visibility = View.VISIBLE
                    lyricsCoordinator.alpha = 1f
                    lyricsCoordinator.visibility = View.VISIBLE
                    behavior.isDraggable = true
                    expandAnimator = null
                    endSharedAlbumArt()
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (expandAnimator !== animation) return
                    playerPanel.translationY = 0f
                    behavior.isDraggable = true
                    expandAnimator = null
                    endSharedAlbumArt()
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

    fun applyTopInset(systemBarsTop: Int) {
        setTopMargin(btnPanelBack, baseBtnPanelBackMarginTop + systemBarsTop)
        setTopMargin(btnPanelSleepTimer, baseBtnPanelSleepTimerMarginTop + systemBarsTop)
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

    private fun setTopMargin(view: View, marginPx: Int) {
        val params = view.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (params.topMargin == marginPx) return
        params.topMargin = marginPx
        view.layoutParams = params
    }

    private fun coldExpand() {
        if (!isReady || !coldExpandInProgress) return
        if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
            coldExpandInProgress = false
            behavior.isDraggable = true
            playerPanel.translationY = 0f
            return
        }

        val startOffset = playerPanel.height.toFloat()
        if (startOffset <= 0f) {
            playerPanel.translationY = 0f
            coldExpandInProgress = false
            behavior.isDraggable = true
            audioSpectrumView.start()
            onExpanded()
            return
        }

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
                    if (expandAnimator !== animation) return
                    playerPanel.translationY = 0f
                    expandAnimator = null
                    coldExpandInProgress = false
                    behavior.isDraggable = true
                    audioSpectrumView.start()
                    onExpanded()
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (expandAnimator !== animation) return
                    expandAnimator = null
                    coldExpandInProgress = false
                    playerPanel.translationY = 0f
                    behavior.isDraggable = true
                }
            })
            start()
        }
    }

    // --- Shared element de la caratula (mini <-> panel) ---
    //
    // progress = 0  -> caratula en la posicion/tamano/esquinas del mini
    //                  reproductor.
    // progress = 1  -> caratula en la posicion/tamano/esquinas del panel
    //                  expandido.
    // La direccion (expandiendo o colapsando) no importa: siempre se
    // interpola de mini a panel con el mismo progress, asi que sirve tanto
    // para smoothExpand() como para el onSlide() de un arrastre o de un
    // collapse() por boton de atras.
    private fun updateSharedAlbumArt(progress: Float) {
        if (progress <= 0.001f || progress >= 0.999f) {
            endSharedAlbumArt()
            return
        }

        if (!sharedAlbumArt.isActive) {
            val started = sharedAlbumArt.begin(ivMiniAlbumArt, miniArtCornerRadiusPx)
            if (!started) return
            ivMiniAlbumArt.alpha = 0f
            ivPanelAlbumArt.alpha = 0f
        }

        sharedAlbumArt.update(
            ivMiniAlbumArt,
            ivPanelAlbumArt,
            miniArtCornerRadiusPx,
            panelArtCornerRadiusPx,
            progress
        )
    }

    private fun endSharedAlbumArt() {
        if (!sharedAlbumArt.isActive) return
        sharedAlbumArt.end()
        ivMiniAlbumArt.alpha = 1f
        ivPanelAlbumArt.alpha = 1f
    }

    private fun View.doOnLayoutCompat(action: () -> Unit) {
        if (isLaidOut) post(action) else {
            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    removeOnLayoutChangeListener(this)
                    action()
                }
            })
        }
    }
}