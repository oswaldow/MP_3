package com.learnlayout.mp_3

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior

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

    private var lyricsAreSynced: Boolean = false
    private var lyricsBannerScrollAnimator: ValueAnimator? = null
    private var currentLyricsResult: LyricsResult? = null

    // ---------- Bloqueo del banner mientras esta expandido ----------
    //
    // El banner NO se cierra solo por hacer scroll dentro de la letra,
    // ni hacia arriba ni hacia abajo: mientras este expandido, el
    // unico mecanismo que puede colapsarlo es el nested scrolling
    // nativo de RecyclerView + BottomSheetBehavior, que solo entra en
    // accion cuando la lista ya esta en su tope (posicion 0, primera
    // linea visible) y el usuario sigue arrastrando hacia abajo desde
    // ahi. Mientras haya contenido por encima sin ver, ese gesto se
    // queda dentro del RecyclerView como scroll normal y el banner
    // permanece "bloqueado" en su lugar.
    //
    // (Antes existia aqui un gesto extra para cerrar con un swipe
    // rapido desde cualquier parte de la letra, pero eso hacia que un
    // scroll normal y rapido tambien cerrara el banner por accidente.
    // Se elimino a proposito.)

    private val baseLyricsPanelPaddingTop: Int =
        lyricsPanel.paddingTop

    private val density =
        activity.resources.displayMetrics.density

    private val collapsedRadiusPx =
        8f * density

    private val expandedRadiusPx =
        28f * density

    // Color de fondo por defecto.
    private val defaultPanelColor: Int =
        ContextCompat.getColor(
            activity,
            R.color.surface_dark
        )

    private val panelBackground =
        GradientDrawable().apply {

            setColor(defaultPanelColor)

            // El borde siempre queda blanco.
            setStroke(
                (2 * density).toInt(),
                ContextCompat.getColor(
                    activity,
                    R.color.lyrics_banner_border
                )
            )

            cornerRadii =
                radiiFor(collapsedRadiusPx)
        }

    private fun radiiFor(
        topRadius: Float
    ): FloatArray =
        floatArrayOf(
            topRadius,
            topRadius,

            topRadius,
            topRadius,

            0f,
            0f,

            0f,
            0f
        )

    val isExpanded: Boolean
        get() =
            ::behavior.isInitialized &&
                    (
                            behavior.state ==
                                    BottomSheetBehavior.STATE_EXPANDED ||
                                    behavior.state ==
                                    BottomSheetBehavior.STATE_HALF_EXPANDED
                            )

    val isCoordinatorVisible: Boolean
        get() =
            lyricsCoordinator.visibility == View.VISIBLE

    init {

        lyricsPanel.background = panelBackground

        // No sobreescribimos canScrollVertically().
        //
        // El RecyclerView conserva su nested scrolling normal.
        //
        // Swipe hacia arriba:
        // BottomSheetBehavior maneja la expansion.
        //
        // Swipe hacia abajo:
        // si la lista ya esta hasta arriba, BottomSheetBehavior puede
        // colapsar normalmente. Si no, el gesto se queda como scroll
        // normal dentro de la letra y el banner no se mueve.

        rvLyricsPanel.layoutManager =
            LinearLayoutManager(activity)

        rvLyricsPanel.isNestedScrollingEnabled = true

        rvLyricsPanel.itemAnimator = null
    }

    fun setup() {

        behavior =
            BottomSheetBehavior.from(lyricsPanel)

        behavior.isHideable = false

        behavior.skipCollapsed = false

        behavior.state =
            BottomSheetBehavior.STATE_COLLAPSED

        behavior.setFitToContents(false)

        rvLyricsPanel.alpha = 1f

        rvLyricsPanel.visibility =
            View.VISIBLE

        btnSaveLyrics.setOnClickListener {
            toggleSaveLyrics()
        }

        behavior.addBottomSheetCallback(
            object :
                BottomSheetBehavior.BottomSheetCallback() {

                override fun onStateChanged(
                    bottomSheet: View,
                    newState: Int
                ) {

                    // El estado "a medias" (HALF_EXPANDED) queda casi
                    // a la misma altura que el expandido (ver el
                    // calculo de halfExpandedRatio en
                    // applyWindowInsets), asi que solo generaba un
                    // doble asentamiento visual al alzar el banner.
                    //
                    // Lo saltamos directo a EXPANDED para que abrir
                    // el banner sea un solo movimiento limpio.
                    if (
                        newState ==
                        BottomSheetBehavior.STATE_HALF_EXPANDED
                    ) {

                        behavior.state =
                            BottomSheetBehavior.STATE_EXPANDED

                        return
                    }

                    // Ya no forzamos un scroll automatico a la linea
                    // activa al expandir. El banner se queda donde ya
                    // estaba (normalmente ya sincronizado desde el
                    // modo colapsado) y de ahi en adelante el usuario
                    // puede desplazarse libremente con el dedo, igual
                    // que en los comentarios de TikTok. La linea
                    // activa se sigue iluminando sola (ver
                    // syncWithPosition / animateActiveLineChange),
                    // eso no depende del scroll.

                    val target =
                        if (
                            newState ==
                            BottomSheetBehavior.STATE_COLLAPSED
                        ) {
                            collapsedRadiusPx
                        } else {
                            expandedRadiusPx
                        }

                    panelBackground.cornerRadii =
                        radiiFor(target)
                }

                override fun onSlide(
                    bottomSheet: View,
                    slideOffset: Float
                ) {

                    val t =
                        slideOffset.coerceIn(0f, 1f)

                    val radius =
                        collapsedRadiusPx +
                                (
                                        expandedRadiusPx -
                                                collapsedRadiusPx
                                        ) * t

                    panelBackground.cornerRadii =
                        radiiFor(radius)
                }
            }
        )
    }

    // ---------- Color del fondo ----------

    /**
     * Llamado desde PlayerPanelController cada vez
     * que hay una caratula nueva.
     */
    fun applyAlbumArtColor(bitmap: Bitmap) {

        PlayerPaletteTheme.applyToDrawable(
            bitmap,
            panelBackground,
            defaultPanelColor
        )
    }

    /**
     * Llamado cuando la cancion actual no tiene caratula.
     */
    fun applyAlbumArtFallback() {

        PlayerPaletteTheme.applyDrawableFallback(
            panelBackground,
            defaultPanelColor
        )
    }

    // ---------- Drag guard ----------

    fun isPointInside(
        rawX: Float,
        rawY: Float
    ): Boolean {

        val rect = Rect()

        lyricsPanel.getGlobalVisibleRect(rect)

        return rect.contains(
            rawX.toInt(),
            rawY.toInt()
        )
    }

    // ---------- Ciclo de vida ligado al panel ----------

    fun collapse() {

        if (::behavior.isInitialized) {

            behavior.state =
                BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    fun onPlayerPanelExpanded() {

        // La visibilidad/alpha de lyricsCoordinator ya no se maneja aqui:
        // PlayerPanelAnimationController la sincroniza cuadro a cuadro con
        // groupExpanded (mismo alpha, mismo progress) para que el banner de
        // letra suba pegado al resto del panel expandido, sin aparecer de
        // golpe. Aqui solo queda lo que le corresponde a esta clase.

        updatePeekHeight()
    }

    fun onPlayerPanelCollapsed() {

        collapse()
    }

    // ---------- Insets / edge-to-edge ----------

    fun applyWindowInsets(
        systemBarsTop: Int,
        rootHeight: Int
    ) {

        lyricsPanel.setPadding(
            lyricsPanel.paddingLeft,
            baseLyricsPanelPaddingTop,
            lyricsPanel.paddingRight,
            lyricsPanel.paddingBottom
        )

        if (!::behavior.isInitialized) {
            return
        }

        val extraGap =
            (
                    LYRICS_EXPANDED_TOP_GAP_DP *
                            activity.resources.displayMetrics.density
                    ).toInt()

        val offset =
            systemBarsTop + extraGap

        behavior.setExpandedOffset(offset)

        if (rootHeight > 0) {

            val ratio =
                (
                        1f -
                                offset.toFloat() /
                                rootHeight
                        ).coerceIn(
                        0.05f,
                        0.95f
                    )

            behavior.setHalfExpandedRatio(ratio)
        }
    }

    fun updatePeekHeight() {

        groupExpanded.post {

            if (!::behavior.isInitialized) {
                return@post
            }

            if (
                groupExpanded.visibility !=
                View.VISIBLE ||
                groupExpanded.height == 0
            ) {
                return@post
            }

            val controlsLocation =
                IntArray(2)

            llPanelControls.getLocationOnScreen(
                controlsLocation
            )

            val panelLocation =
                IntArray(2)

            groupExpanded.getLocationOnScreen(
                panelLocation
            )

            val controlsBottomOnScreen =
                controlsLocation[1] +
                        llPanelControls.height

            val panelBottomOnScreen =
                panelLocation[1] +
                        groupExpanded.height

            // Separacion extra entre el boton de
            // pausa/reproducir y el banner de letra.
            val gapFromControls =
                (
                        LYRICS_PEEK_GAP_DP *
                                activity.resources.displayMetrics.density
                        ).toInt()

            val freeSpace =
                panelBottomOnScreen -
                        controlsBottomOnScreen -
                        gapFromControls

            val minPeek =
                (72 * density).toInt()

            val newPeek =
                freeSpace.coerceAtLeast(minPeek)

            if (
                newPeek != behavior.peekHeight
            ) {

                behavior.peekHeight =
                    newPeek
            }
        }
    }

    // ---------- Carga de letra ----------

    fun resetSongId() {

        lyricsSongId = null
    }

    /**
     * Muestra la letra de la cancion actual.
     *
     * A pedido: se le da prioridad a lo que el propio archivo trae
     * embebido por sobre cualquier letra ya guardada (SavedLyricsRepository),
     * salvo que esa letra guardada haya sido elegida a mano por el usuario
     * (ver [SavedLyricsRepository.isManual]) -- en ese caso se respeta la
     * eleccion manual y no se vuelve a mirar el archivo.
     *
     * Esto implica abrir y parsear el archivo de audio completo cada vez
     * que se cambia de cancion (salvo que tenga letra manual), es
     * intencional pese al costo de I/O.
     */
    fun loadForSong(song: Song) {

        if (lyricsSongId == song.id) {
            return
        }

        lyricsSongId = song.id

        lyricsRequestId++

        btnPanelLyricsSync.visibility =
            View.GONE

        showLyricsMessage(
            "Buscando letra en el archivo..."
        )

        loadEmbeddedLyricsFirst(song)
    }

    /**
     * Revisa primero si [song] ya trae letra embebida en el archivo de
     * audio (ver EmbeddedMetadataReader) y le da prioridad a eso. Solo si
     * el archivo no trae nada se cae a lo que ya hubiera guardado
     * SavedLyricsRepository (descarga previa o eleccion manual).
     *
     * Excepcion: si la letra guardada de [song] ya fue elegida a mano por
     * el usuario (picker de candidatos o sincronizacion manual), esa
     * eleccion se respeta directamente y no se toca el archivo.
     */
    private fun loadEmbeddedLyricsFirst(song: Song) {

        val requestId = lyricsRequestId

        AppExecutors.runInBackground {

            val manualSaved = SavedLyricsRepository.getSavedLyrics(activity, song.id)
                .takeIf { SavedLyricsRepository.isManual(activity, song.id) }

            val embedded = if (manualSaved == null) {
                EmbeddedMetadataReader.readLyrics(activity, song)
            } else {
                null
            }

            AppExecutors.runOnMain {

                // La cancion pudo haber cambiado, o la letra ya pudo
                // haberse guardado por otro camino (selector manual),
                // mientras se leia el archivo en el hilo de fondo.
                if (requestId != lyricsRequestId || lyricsSongId != song.id) {
                    return@runOnMain
                }

                when {

                    manualSaved != null -> {

                        showLyricsResult(manualSaved, song.id)
                    }

                    embedded != null -> {

                        SavedLyricsRepository.save(activity, song.id, embedded)

                        showLyricsResult(embedded, song.id)
                    }

                    else -> {

                        val saved = SavedLyricsRepository.getSavedLyrics(activity, song.id)

                        if (saved != null) {

                            showLyricsResult(saved, song.id)

                        } else {

                            btnPanelLyricsSync.visibility =
                                View.GONE

                            showLyricsMessage(
                                "Sin letra guardada. Manten presionada la caratula para buscarla"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Vuelve a leer SavedLyricsRepository para [song]
     * y refresca el panel si es la cancion vigente.
     */
    fun reloadSavedLyrics(song: Song) {

        if (lyricsSongId != song.id) {
            return
        }

        val saved =
            SavedLyricsRepository.getSavedLyrics(
                activity,
                song.id
            )

        if (saved != null) {

            showLyricsResult(
                saved,
                song.id
            )
        }
    }

    /**
     * Fuerza la actualizacion visual de las letras
     * aunque el ID no haya cambiado.
     */
    fun refreshForMetadataChange(song: Song) {

        lyricsSongId = null

        loadForSong(song)
    }

    private fun showLyricsResult(
        result: LyricsResult,
        songId: Long
    ) {

        val hasContent =
            !result.isInstrumental &&
                    (
                            !result.syncedLines.isNullOrEmpty() ||
                                    !result.plainLyrics.isNullOrBlank()
                            )

        when {

            result.isInstrumental -> {

                showLyricsMessage(
                    "Esta cancion es instrumental"
                )
            }

            !result.syncedLines.isNullOrEmpty() -> {

                showSyncedLyrics(
                    result.syncedLines
                )
            }

            !result.plainLyrics.isNullOrBlank() -> {

                showPlainLyrics(
                    result.plainLyrics
                )
            }

            else -> {

                showLyricsMessage(
                    "No se encontro letra para esta cancion"
                )
            }
        }

        btnPanelLyricsSync.visibility =
            if (hasContent) {
                View.VISIBLE
            } else {
                View.GONE
            }

        if (hasContent) {

            currentLyricsResult = result

            btnSaveLyrics.visibility =
                View.VISIBLE

            updateSaveLyricsIcon(
                SavedLyricsRepository.isSaved(
                    activity,
                    songId
                )
            )
        }
    }

    private fun updateSaveLyricsIcon(
        saved: Boolean
    ) {

        btnSaveLyrics.setImageResource(

            if (saved) {
                R.drawable.ic_favorite
            } else {
                R.drawable.ic_favorite_border
            }
        )
    }

    fun toggleSaveLyrics() {

        val songId =
            lyricsSongId ?: return

        val result =
            currentLyricsResult ?: return

        val nowSaved =
            SavedLyricsRepository.toggleSaved(
                activity,
                songId,
                result
            )

        updateSaveLyricsIcon(nowSaved)

        Toast.makeText(
            activity,
            if (nowSaved) {
                "Letra guardada"
            } else {
                "Letra eliminada de guardadas"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showLyricsMessage(
        message: String
    ) {

        lyricsAdapter = null

        lyricsAreSynced = false

        currentLyricsResult = null

        btnSaveLyrics.visibility =
            View.GONE

        rvLyricsPanel.adapter =
            LyricsLineAdapter(
                listOf(
                    LyricsLine(
                        timeMs = -1,
                        text = message
                    )
                )
            )
    }

    private fun showSyncedLyrics(
        lines: List<LyricsLine>
    ) {

        val adapter =
            LyricsLineAdapter(lines)

        lyricsAdapter = adapter

        lyricsAreSynced = true

        rvLyricsPanel.adapter =
            adapter
    }

    private fun showPlainLyrics(
        text: String
    ) {

        val staticLines =
            text.lines()
                .filter {
                    it.isNotBlank()
                }
                .map {
                    LyricsLine(
                        timeMs = -1,
                        text = it
                    )
                }

        val adapter =
            LyricsLineAdapter(staticLines)

        lyricsAdapter = adapter

        // Letra sin sincronizar:
        // no tiene timestamps reales.
        lyricsAreSynced = false

        rvLyricsPanel.adapter =
            adapter
    }

    // ---------- Sincronizacion con la posicion ----------

    fun syncWithPosition(
        positionMs: Long
    ) {

        if (!lyricsAreSynced) {
            return
        }

        val adapter =
            lyricsAdapter ?: return

        val previousIndex =
            adapter.getActiveIndex()

        val newIndex =
            adapter.updateActiveLine(positionMs)

        if (newIndex < 0) {
            return
        }

        adapter.animateActiveLineChange(
            rvLyricsPanel,
            previousIndex,
            newIndex
        )

        if (!isExpanded) {

            scrollToLine(newIndex)
        }
    }

    private fun scrollToLine(
        index: Int
    ) {

        if (index < 0) {
            return
        }

        rvLyricsPanel.post {

            val layoutManager =
                rvLyricsPanel.layoutManager
                        as? LinearLayoutManager
                    ?: return@post

            if (isExpanded) {

                rvLyricsPanel.stopScroll()

                val smoothScroller =
                    object :
                        LinearSmoothScroller(activity) {

                        override fun getVerticalSnapPreference():
                                Int {
                            return SNAP_TO_START
                        }

                        override fun calculateSpeedPerPixel(
                            displayMetrics:
                            android.util.DisplayMetrics
                        ): Float {

                            return 70f /
                                    displayMetrics.densityDpi
                        }
                    }

                smoothScroller.targetPosition =
                    index

                layoutManager.startSmoothScroll(
                    smoothScroller
                )

                return@post
            }

            // Colapsado:
            // animamos manualmente la posicion
            // de la linea activa.

            lyricsBannerScrollAnimator?.cancel()

            val fallbackStartOffset =
                (40 * density).toInt()

            val startOffset =
                layoutManager
                    .findViewByPosition(index)
                    ?.top
                    ?: fallbackStartOffset

            if (startOffset <= 0) {

                layoutManager.scrollToPositionWithOffset(
                    index,
                    0
                )

                return@post
            }

            val animator =
                ValueAnimator.ofInt(
                    startOffset,
                    0
                )

            animator.duration = 260L

            animator.interpolator =
                DecelerateInterpolator()

            animator.addUpdateListener { anim ->

                layoutManager
                    .scrollToPositionWithOffset(
                        index,
                        anim.animatedValue as Int
                    )
            }

            lyricsBannerScrollAnimator =
                animator

            animator.start()
        }
    }

    fun cancelAnimations() {

        lyricsBannerScrollAnimator?.cancel()
    }

    companion object {

        // Separacion en dp entre el boton de
        // pausa/reproducir y el banner colapsado.
        private const val LYRICS_PEEK_GAP_DP = 16f

        // Espacio superior cuando la letra esta expandida.
        private const val LYRICS_EXPANDED_TOP_GAP_DP = 24
    }
}