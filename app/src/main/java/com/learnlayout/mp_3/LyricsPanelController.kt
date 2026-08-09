package com.learnlayout.mp_3

import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * Encapsula el panel deslizable de letra (estilo Spotify): el BottomSheet en
 * si (setup, peek height, insets), la carga de letra via LyricsRepository /
 * SavedLyricsRepository, el resaltado sincronizado con la posicion de
 * reproduccion, y el boton de guardar letra.
 *
 * [groupExpanded] y [llPanelControls] son vistas del panel del reproductor
 * (no de este controller) que se necesitan solo para calcular cuanto
 * espacio libre queda para el peek height del banner de letra colapsado.
 */
class LyricsPanelController(
    private val activity: AppCompatActivity,
    private val lyricsCoordinator: View,
    private val lyricsPanel: FrameLayout,
    private val rvLyricsPanel: RecyclerView,
    private val btnSaveLyrics: ImageButton,
    private val btnPanelLyricsSync: ImageButton,
    private val groupExpanded: View,
    private val llPanelControls: View
) {

    lateinit var behavior: BottomSheetBehavior<FrameLayout>
        private set

    private var lyricsAdapter: LyricsLineAdapter? = null
    private var lyricsSongId: Long? = null
    private var lyricsRequestId: Int = 0
    // true solo cuando las lineas cargadas tienen timestamps reales (vienen
    // de showSyncedLyrics). La letra plana (showPlainLyrics) usa timeMs=-1
    // en todas sus lineas como "sin tiempo", y si se le aplica el mismo
    // resaltado por posicion, TODAS las lineas cuentan como "ya pasadas"
    // desde el segundo 0 y el resaltado salta directo a la ultima linea.
    // Esta bandera evita que syncWithPosition toque letra sin sincronizar.
    private var lyricsAreSynced: Boolean = false
    private var lyricsBannerScrollAnimator: ValueAnimator? = null
    private var currentLyricsResult: LyricsResult? = null

    private val baseLyricsPanelPaddingTop: Int = lyricsPanel.paddingTop

    val isExpanded: Boolean
        get() = ::behavior.isInitialized &&
                (behavior.state == BottomSheetBehavior.STATE_EXPANDED ||
                        behavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED)

    val isCoordinatorVisible: Boolean
        get() = lyricsCoordinator.visibility == View.VISIBLE

    init {
        rvLyricsPanel.layoutManager = object : LinearLayoutManager(activity) {
            override fun canScrollVertically(): Boolean = isExpanded
        }
        rvLyricsPanel.isNestedScrollingEnabled = false
        rvLyricsPanel.itemAnimator = null
    }

    fun setup() {
        behavior = BottomSheetBehavior.from(lyricsPanel)
        behavior.isHideable = false
        behavior.skipCollapsed = false
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        behavior.setFitToContents(false)

        rvLyricsPanel.alpha = 1f
        rvLyricsPanel.visibility = View.VISIBLE

        btnSaveLyrics.setOnClickListener {
            toggleSaveLyrics()
        }

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED ||
                    newState == BottomSheetBehavior.STATE_HALF_EXPANDED
                ) {
                    lyricsAdapter?.let { scrollToLine(it.getActiveIndex()) }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })
    }

    // ---------- Drag guard (usado por dispatchTouchEvent de la Activity) ----------

    fun isPointInside(rawX: Float, rawY: Float): Boolean {
        val rect = Rect()
        lyricsPanel.getGlobalVisibleRect(rect)
        return rect.contains(rawX.toInt(), rawY.toInt())
    }

    // ---------- Ciclo de vida ligado al panel del reproductor ----------

    fun collapse() {
        if (::behavior.isInitialized) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    fun onPlayerPanelExpanded() {
        lyricsCoordinator.visibility = View.VISIBLE
        updatePeekHeight()
    }

    fun onPlayerPanelCollapsed() {
        collapse()
        lyricsCoordinator.visibility = View.GONE
    }

    // ---------- Insets / edge-to-edge ----------

    fun applyWindowInsets(systemBarsTop: Int, rootHeight: Int) {
        lyricsPanel.setPadding(
            lyricsPanel.paddingLeft,
            baseLyricsPanelPaddingTop,
            lyricsPanel.paddingRight,
            lyricsPanel.paddingBottom
        )

        if (!::behavior.isInitialized) return

        val extraGap = (LYRICS_EXPANDED_TOP_GAP_DP * activity.resources.displayMetrics.density).toInt()
        val offset = systemBarsTop + extraGap
        behavior.setExpandedOffset(offset)

        if (rootHeight > 0) {
            val ratio = (1f - offset.toFloat() / rootHeight).coerceIn(0.05f, 0.95f)
            behavior.setHalfExpandedRatio(ratio)
        }
    }

    fun updatePeekHeight() {
        groupExpanded.post {
            if (!::behavior.isInitialized) return@post
            if (groupExpanded.visibility != View.VISIBLE || groupExpanded.height == 0) return@post

            val controlsLocation = IntArray(2)
            llPanelControls.getLocationOnScreen(controlsLocation)
            val panelLocation = IntArray(2)
            groupExpanded.getLocationOnScreen(panelLocation)

            val controlsBottomOnScreen = controlsLocation[1] + llPanelControls.height
            val panelBottomOnScreen = panelLocation[1] + groupExpanded.height

            // Separacion extra entre el boton de pausa/reproducir y el
            // banner de letra colapsado, para que no queden pegados.
            val gapFromControls = (LYRICS_PEEK_GAP_DP * activity.resources.displayMetrics.density).toInt()
            val freeSpace = panelBottomOnScreen - controlsBottomOnScreen - gapFromControls

            val minPeek = (72 * activity.resources.displayMetrics.density).toInt()
            val newPeek = freeSpace.coerceAtLeast(minPeek)
            if (newPeek != behavior.peekHeight) {
                behavior.peekHeight = newPeek
            }
        }
    }

    // ---------- Carga de letra ----------

    fun resetSongId() {
        lyricsSongId = null
    }

    /**
     * Pide la letra sincronizada de la cancion actual y la deja lista en el
     * panel. Se ignora si la respuesta llega para una peticion vieja
     * (cancion ya cambiada) usando lyricsRequestId.
     */
    fun loadForSong(song: Song) {
        if (lyricsSongId == song.id) return
        lyricsSongId = song.id
        lyricsRequestId++
        val requestId = lyricsRequestId

        val saved = SavedLyricsRepository.getSavedLyrics(activity, song.id)
        if (saved != null) {
            showLyricsResult(saved, song.id)
            return
        }

        btnPanelLyricsSync.visibility = View.GONE
        showLyricsMessage("Buscando letra...")

        val durationSeconds = song.duration / 1000
        LyricsRepository.fetch(song.title, song.artist, durationSeconds, object : LyricsRepository.LyricsCallback {
            override fun onSuccess(result: LyricsResult) {
                if (requestId != lyricsRequestId) return
                showLyricsResult(result, song.id)
            }

            override fun onError(message: String) {
                if (requestId != lyricsRequestId) return
                showLyricsMessage(message)
            }
        })
    }

    private fun showLyricsResult(result: LyricsResult, songId: Long) {
        val hasContent = !result.isInstrumental &&
                (!result.syncedLines.isNullOrEmpty() || !result.plainLyrics.isNullOrBlank())

        when {
            result.isInstrumental -> showLyricsMessage("Esta cancion es instrumental")
            !result.syncedLines.isNullOrEmpty() -> showSyncedLyrics(result.syncedLines)
            !result.plainLyrics.isNullOrBlank() -> showPlainLyrics(result.plainLyrics)
            else -> showLyricsMessage("No se encontro letra para esta cancion")
        }

        btnPanelLyricsSync.visibility = if (hasContent) View.VISIBLE else View.GONE

        if (hasContent) {
            currentLyricsResult = result
            btnSaveLyrics.visibility = View.VISIBLE
            updateSaveLyricsIcon(SavedLyricsRepository.isSaved(activity, songId))
        }
    }

    private fun updateSaveLyricsIcon(saved: Boolean) {
        btnSaveLyrics.setImageResource(
            if (saved) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

    fun toggleSaveLyrics() {
        val songId = lyricsSongId ?: return
        val result = currentLyricsResult ?: return
        val nowSaved = SavedLyricsRepository.toggleSaved(activity, songId, result)
        updateSaveLyricsIcon(nowSaved)
        Toast.makeText(
            activity,
            if (nowSaved) "Letra guardada" else "Letra eliminada de guardadas",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showLyricsMessage(message: String) {
        lyricsAdapter = null
        lyricsAreSynced = false
        currentLyricsResult = null
        btnSaveLyrics.visibility = View.GONE
        rvLyricsPanel.adapter = LyricsLineAdapter(listOf(LyricsLine(timeMs = -1, text = message)))
    }

    private fun showSyncedLyrics(lines: List<LyricsLine>) {
        val adapter = LyricsLineAdapter(lines)
        lyricsAdapter = adapter
        lyricsAreSynced = true
        rvLyricsPanel.adapter = adapter
    }

    private fun showPlainLyrics(text: String) {
        val staticLines = text.lines()
            .filter { it.isNotBlank() }
            .map { LyricsLine(timeMs = -1, text = it) }
        val adapter = LyricsLineAdapter(staticLines)
        lyricsAdapter = adapter
        // Letra sin sincronizar: no tiene timestamps reales, asi que no se
        // debe intentar resaltar ni auto-scrollear por posicion (ver
        // syncWithPosition). Se queda estatica, el usuario la lee
        // desplazandose manualmente en modo expandido.
        lyricsAreSynced = false
        rvLyricsPanel.adapter = adapter
    }

    // ---------- Sincronizacion con la posicion de reproduccion ----------

    fun syncWithPosition(positionMs: Long) {
        if (!lyricsAreSynced) return
        val adapter = lyricsAdapter ?: return
        val previousIndex = adapter.getActiveIndex()
        val newIndex = adapter.updateActiveLine(positionMs)
        if (newIndex < 0) return

        adapter.animateActiveLineChange(rvLyricsPanel, previousIndex, newIndex)

        if (!isExpanded) {
            scrollToLine(newIndex)
        }
    }

    private fun scrollToLine(index: Int) {
        if (index < 0) return
        rvLyricsPanel.post {
            val layoutManager = rvLyricsPanel.layoutManager as? LinearLayoutManager ?: return@post

            if (isExpanded) {
                rvLyricsPanel.stopScroll()
                val smoothScroller = object : LinearSmoothScroller(activity) {
                    override fun getVerticalSnapPreference(): Int = SNAP_TO_START
                    override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
                        return 70f / displayMetrics.densityDpi
                    }
                }
                smoothScroller.targetPosition = index
                layoutManager.startSmoothScroll(smoothScroller)
                return@post
            }

            // Colapsado (banner): el layoutManager tiene canScrollVertically()
            // atado a isExpanded, o sea que aqui devuelve false a proposito
            // (para que el usuario no pueda arrastrar la letra con el
            // dedo). Eso mismo hace que RecyclerView.smoothScrollBy /
            // LinearSmoothScroller no muevan nada (ponen el desplazamiento
            // en 0), por eso la linea activa se quedaba atras y "bajaba" en
            // vez de quedarse fija arriba. Por eso aqui se anima el offset
            // a mano con un ValueAnimator llamando directo a
            // scrollToPositionWithOffset (eso si funciona, no pasa por
            // smoothScrollBy) y siempre termina exacto en offset 0, para
            // que la linea activa quede pegada arriba igual que antes.
            lyricsBannerScrollAnimator?.cancel()

            val density = activity.resources.displayMetrics.density
            val fallbackStartOffset = (40 * density).toInt()
            val startOffset = layoutManager.findViewByPosition(index)?.top ?: fallbackStartOffset

            if (startOffset <= 0) {
                layoutManager.scrollToPositionWithOffset(index, 0)
                return@post
            }

            val animator = ValueAnimator.ofInt(startOffset, 0)
            animator.duration = 260L
            animator.interpolator = DecelerateInterpolator()
            animator.addUpdateListener { anim ->
                layoutManager.scrollToPositionWithOffset(index, anim.animatedValue as Int)
            }
            lyricsBannerScrollAnimator = animator
            animator.start()
        }
    }

    fun cancelAnimations() {
        lyricsBannerScrollAnimator?.cancel()
    }

    companion object {
        // Separacion en dp entre el boton de pausa/reproducir y el banner
        // de letra colapsado (el "peek" del panel deslizable de letra).
        private const val LYRICS_PEEK_GAP_DP = 16f
        private const val LYRICS_EXPANDED_TOP_GAP_DP = 24
    }
}