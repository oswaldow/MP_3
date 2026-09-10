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
import android.view.animation.AccelerateDecelerateInterpolator
import android.util.Log

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

        // Duracion propia de smoothExpand() (toque desde Home/mini player
        // con musica ya sonando). El usuario pidio que se vea el panel
        // subir de forma gradual y bien lenta, aunque tarde mas, en vez de
        // un salto rapido. coldExpand() sigue usando
        // EXPAND_ANIM_DURATION_MS sin cambios.
        //
        // Historial: esta constante originalmente no se usaba en absoluto
        // (smoothExpand dependia enteramente del "settle" interno de
        // BottomSheetBehavior, con su propia duracion fija ~400ms no
        // configurable). Se agrego un ValueAnimator propio para
        // controlarla, pero en un primer intento el panel se volvia
        // visible ANTES de que el settle oculto terminara realmente
        // (ver revealAfterHiddenSettle), asi que el settle nativo seguia
        // moviendo la posicion real del panel al mismo tiempo que nuestro
        // propio animador -de ahi el "se traba a momentos" reportado-.
        // Ahora la revelacion espera la confirmacion real de
        // STATE_EXPANDED antes de arrancar esta animacion, asi que el
        // valor de aca ya se refleja fielmente en pantalla.
        private const val SMOOTH_EXPAND_ANIM_DURATION_MS = 550L

        // Fraccion del progreso (0..1) en la que ocurre todo el crossfade
        // mini<->expandido. Con 0.5f el intercambio de opacidad termina a
        // mitad de camino y el resto del gesto solo termina de deslizar el
        // panel ya con groupExpanded a alpha 1 (mismo "ritmo" visual que
        // tenia antes, pero ahora sin hueco de opacidad simultanea: ver
        // onSlide() y el addUpdateListener de revealAfterHiddenSettle()).
        private const val CROSSFADE_SPAN = 0.5f

        // Mismos radios que PlayerPanelController.applyRoundedCorners() usa
        // para ivMiniAlbumArt / ivPanelAlbumArt: deben coincidir para que la
        // vista "volante" no pegue un salto de esquinas al empezar/terminar.
        private const val MINI_ART_CORNER_RADIUS_DP = 6f
        private const val PANEL_ART_CORNER_RADIUS_DP = 10f
    }

    private lateinit var behavior: BottomSheetBehavior<FrameLayout>
    private var coldExpandInProgress = false
    private var expandAnimator: ValueAnimator? = null

    // Flag hermano de coldExpandInProgress, pero para smoothExpand(). Mientras
    // esta activo, onStateChanged() NO debe tocar alpha/visibility "normal"
    // ni disparar onExpanded() cuando llegue a STATE_EXPANDED: en vez de eso
    // dispara revealAfterHiddenSettle(), que recien ahi (settle realmente
    // terminado, panel todavia GONE) mide la distancia real y arranca nuestra
    // propia animacion de punta a punta.
    private var smoothExpandInProgress = false
    private var smoothExpandAnimator: ValueAnimator? = null

    // "top" del panel justo antes de disparar el cambio de estado a
    // EXPANDED (o sea, en su posicion colapsada real). Se usa en
    // revealAfterHiddenSettle() para calcular la distancia real a animar.
    private var smoothExpandStartTop = 0

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
                Log.d(TAG, "onStateChanged newState=$newState coldExpandInProgress=$coldExpandInProgress " +
                        "smoothExpandInProgress=$smoothExpandInProgress translationY=${playerPanel.translationY} " +
                        "panelVisibility=${playerPanel.visibility}")
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        if (smoothExpandInProgress) {
                            // El settle oculto (panel en GONE) recien
                            // termino de verdad: se posterga un frame con
                            // post() para asegurar que el ultimo layout
                            // pass ya escribio el "top" final, y recien
                            // ahi se revela el panel y arranca nuestra
                            // propia animacion (ver revealAfterHiddenSettle).
                            // Antes se revelaba el panel de forma sincrona
                            // apenas se pedia el cambio de estado (sin
                            // esperar a este callback), lo que dejaba el
                            // settle nativo -que tarda ~400ms en terminar-
                            // corriendo a la vista al mismo tiempo que
                            // nuestro propio ValueAnimator: dos animaciones
                            // moviendo la misma posicion a la vez, que se
                            // sentia como un traqueteo/stutter.
                            playerPanel.post { revealAfterHiddenSettle() }
                            return
                        }

                        groupMini.alpha = 0f
                        groupExpanded.alpha = 1f
                        lyricsCoordinator.alpha = 1f
                        groupMini.visibility = View.INVISIBLE
                        groupExpanded.visibility = View.VISIBLE
                        lyricsCoordinator.visibility = View.VISIBLE
                        endSharedAlbumArt()

                        if (coldExpandInProgress) return

                        // Devuelve el arrastre manual: lo desactivamos al
                        // arrancar smoothExpand() para que el usuario no
                        // interrumpa el settle nativo a mitad de camino.
                        behavior.isDraggable = true
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
                // Mientras nuestra propia animacion de smoothExpand() esta
                // corriendo (o esperando a revelarse), ella ya actualiza
                // alpha/caratula compartida en cada frame segun su propio
                // progreso; no hace falta (ni conviene) que onSlide()
                // tambien lo haga, porque el slideOffset que reporta
                // BottomSheetBehavior durante un settle "oculto" no
                // corresponde a nuestra curva de animacion real.
                if (smoothExpandInProgress) return

                val progress = slideOffset.coerceIn(0f, 1f)
                // Crossfade real: expandedAlpha + miniAlpha siempre suman 1
                // durante todo el tramo de transicion (0..CROSSFADE_SPAN), asi
                // que nunca hay un instante en que ambos esten casi del todo
                // transparentes a la vez. Antes groupMini se apagaba de 0 a
                // 0.5 y groupExpanded empezaba a encenderse recien en 0.4,
                // dejando un hueco (~0.4-0.5) donde los dos estaban casi
                // invisibles y se veia el negro de fondo detras del panel.
                val expandedAlpha = (progress / CROSSFADE_SPAN).coerceIn(0f, 1f)
                groupMini.alpha = 1f - expandedAlpha
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
        smoothExpandAnimator?.cancel()
        smoothExpandAnimator = null
        smoothExpandInProgress = false
        coldExpandInProgress = false
        playerPanel.translationY = 0f
        // Si collapse() llega mientras smoothExpand() todavia tenia el
        // panel oculto esperando el settle (p.ej. el usuario toco "atras"
        // muy rapido), hay que forzar la visibilidad de vuelta: nada mas
        // la restaura en ese escenario, ya que revealAfterHiddenSettle()
        // nunca llegaria a ejecutarse (smoothExpandInProgress ya en false).
        playerPanel.visibility = View.VISIBLE
        if (isReady) {
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    fun expandWhenReady() {
        if (!isReady || behavior.state == BottomSheetBehavior.STATE_EXPANDED) return

        expandAnimator?.cancel()
        smoothExpandAnimator?.cancel()
        smoothExpandAnimator = null
        smoothExpandInProgress = false
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

        expandAnimator?.cancel()
        expandAnimator = null
        smoothExpandAnimator?.cancel()
        smoothExpandAnimator = null

        Log.d(TAG, "smoothExpand() escondiendo panel hasta que el settle real termine")

        // A diferencia del primer intento (que revelaba el panel de forma
        // sincrona, en el mismo frame en que se pedia el cambio de
        // estado), aca dejamos el panel oculto (GONE) y NO lo volvemos a
        // mostrar todavia. El settle interno de BottomSheetBehavior corre
        // sin que se vea nada -exactamente igual que en coldExpand()-, y
        // solo cuando el callback de arriba confirme STATE_EXPANDED real
        // (settle terminado) se revela el panel y arranca nuestra propia
        // animacion (ver revealAfterHiddenSettle). Asi nunca hay dos
        // animaciones moviendo la posicion del panel al mismo tiempo.
        smoothExpandStartTop = playerPanel.top

        smoothExpandInProgress = true
        behavior.isDraggable = false

        // Punto de partida visual: el mismo que ya tenia -groupMini
        // visible/opaco, groupExpanded invisible/transparente-, para que
        // cuando se revele el panel ya este en el estado correcto.
        groupMini.visibility = View.VISIBLE
        groupMini.alpha = 1f
        groupExpanded.visibility = View.VISIBLE
        groupExpanded.alpha = 0f
        lyricsCoordinator.visibility = View.VISIBLE
        lyricsCoordinator.alpha = 0f
        playerPanel.translationY = 0f

        playerPanel.visibility = View.GONE
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        // OJO: aca NO se vuelve a poner VISIBLE. Eso ahora lo hace
        // revealAfterHiddenSettle(), llamado desde onStateChanged() cuando
        // el settle realmente termino.
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

    /**
     * Llamado desde onStateChanged() cuando BottomSheetBehavior confirma
     * STATE_EXPANDED mientras smoothExpand() tenia el panel oculto (GONE).
     * En este punto el settle nativo ya termino de verdad -a diferencia del
     * primer intento, que asumia esto con solo esperar un frame (isLaidOut
     * + post) y a veces revelaba el panel mientras el settle todavia
     * seguia corriendo, produciendo una doble animacion (stutter)-.
     *
     * Mide la distancia real (top viejo colapsado vs. top nuevo ya
     * expandido), recien ahi vuelve a mostrar el panel (ya en su posicion
     * final de verdad, sin settle pendiente) y arranca el ValueAnimator
     * propio que hace toda la subida visible.
     */
    private fun revealAfterHiddenSettle() {
        if (!isReady || !smoothExpandInProgress) return
        if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
            smoothExpandInProgress = false
            behavior.isDraggable = true
            playerPanel.translationY = 0f
            playerPanel.visibility = View.VISIBLE
            return
        }

        val endTop = playerPanel.top
        val startOffset = (smoothExpandStartTop - endTop).toFloat()

        if (startOffset <= 0f) {
            // No hay distancia real que animar (raro, pero por seguridad
            // se cae directo al estado final en vez de dejar algo a medio
            // camino o animar en la direccion equivocada).
            playerPanel.translationY = 0f
            playerPanel.visibility = View.VISIBLE
            finishSmoothExpand()
            return
        }

        playerPanel.translationY = startOffset
        playerPanel.visibility = View.VISIBLE

        smoothExpandAnimator?.cancel()
        smoothExpandAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = SMOOTH_EXPAND_ANIM_DURATION_MS
            // Ease-in-out: el usuario pidio que se "aprecie como sube" de
            // punta a punta y bien lento, no solo que frene al final (a
            // diferencia de coldExpand(), que usa DecelerateInterpolator).
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                playerPanel.translationY = value
                val progress = (1f - (value / startOffset)).coerceIn(0f, 1f)
                val expandedAlpha = (progress / CROSSFADE_SPAN).coerceIn(0f, 1f)
                groupMini.alpha = 1f - expandedAlpha
                groupExpanded.alpha = expandedAlpha
                lyricsCoordinator.alpha = expandedAlpha
                updateSharedAlbumArt(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (smoothExpandAnimator !== animation) return
                    smoothExpandAnimator = null
                    finishSmoothExpand()
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (smoothExpandAnimator !== animation) return
                    smoothExpandAnimator = null
                    // Un cancel a mitad de camino (p.ej. el usuario toco
                    // "atras" mientras subia) lo resuelve collapse(), que ya
                    // limpia smoothExpandInProgress, translationY y
                    // visibility. Aca no forzamos el estado final para no
                    // pisar lo que haya decidido quien cancelo.
                }
            })
            start()
        }
    }

    private fun finishSmoothExpand() {
        playerPanel.translationY = 0f
        groupMini.alpha = 0f
        groupExpanded.alpha = 1f
        lyricsCoordinator.alpha = 1f
        groupMini.visibility = View.INVISIBLE
        groupExpanded.visibility = View.VISIBLE
        lyricsCoordinator.visibility = View.VISIBLE
        endSharedAlbumArt()
        smoothExpandInProgress = false
        behavior.isDraggable = true
        audioSpectrumView.start()
        onExpanded()
    }

    // --- Shared element de la caratula (mini <-> panel) ---
    //
    // progress = 0  -> caratula en la posicion/tamano/esquinas del mini
    //                  reproductor.
    // progress = 1  -> caratula en la posicion/tamano/esquinas del panel
    //                  expandido.
    // La direccion (expandiendo o colapsando) no importa: siempre se
    // interpola de mini a panel con el mismo progress, asi que sirve tanto
    // para revealAfterHiddenSettle() como para el onSlide() de un arrastre
    // o de un collapse() por boton de atras.
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