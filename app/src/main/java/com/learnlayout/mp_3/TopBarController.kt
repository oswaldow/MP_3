package com.learnlayout.mp_3

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.AnimationDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.abs

class TopBarController(
    private val activity: AppCompatActivity,
    private val rootLayout: View,
    private val tvAppName: TextView,
    private val ivMascot: ImageView,
    private val llInlineSearch: View,
    private val etSearch: EditText,
    private val btnSearch: ImageButton,
    private val btnSort: ImageButton,
    private val btnSettings: ImageButton,
    private val tabSongs: TextView,
    private val tabPlaylists: TextView,
    private val rvSongs: View,
    private val rvPlaylists: View,
    private val tvEmptyState: View,
    private val onSearchQueryChanged: (String) -> Unit,
    private val onSortSelected: (SongListActivity.SortType) -> Unit,
    private val isSortReversed: () -> Boolean,
    private val onSortReverseToggled: () -> Unit,
    private val onSongsTabSelected: () -> Unit,
    private val onPlaylistsTabSelected: () -> Unit,
    private val onHomeRequested: () -> Unit
) {

    var isPlaylistsTabActive: Boolean = false
        private set

    private var isHomeActive: Boolean = false
    private var isSearchVisible: Boolean = false
    private var playlistNavigationSession: Boolean = false

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracking = false

    // ---------- Tema dinamico ----------

    private var currentAccentColor: Int = ContextCompat.getColor(activity, R.color.spotify_green)

    // ============================================================
    // SETUP
    // ============================================================

    fun setup() {
        // El encabezado funciona tambien como acceso rapido al Home.
        tvAppName.setOnClickListener { onHomeRequested() }
        ivMascot.setOnClickListener { onHomeRequested() }

        btnSearch.setOnClickListener { toggleInlineSearch() }
        btnSort.setOnClickListener { showSortPopup() }
        btnSettings.setOnClickListener {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onSearchQueryChanged(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // --------------------------------------------------------
        // CANCIONES
        // --------------------------------------------------------

        tabSongs.setOnClickListener { selectSongsTab() }

        // --------------------------------------------------------
        // PLAYLISTS
        // --------------------------------------------------------
        //
        // No se utiliza este boton para entrar desde Canciones.
        //
        // La entrada inicial a Playlists se hace desde Home mediante:
        //
        //     openPlaylistsFromHome()
        //
        // Cuando estamos en Canciones, la pestaña Playlists permanece
        // oculta.
        //
        // Si por alguna razon quedara visible y se toca, solamente
        // permitimos el cambio si existe una sesion de navegacion
        // iniciada desde Playlists.
        //
        tabPlaylists.setOnClickListener {
            if (isPlaylistsTabActive) return@setOnClickListener
            if (!playlistNavigationSession) return@setOnClickListener

            selectPlaylistsTab()
        }

        applyAccentColorToViews()
    }

    // ============================================================
    // ANIMACION DE LA MASCOTA
    // ============================================================

    fun startMascotAnimation() {
        ivMascot.post {
            (ivMascot.drawable as? AnimationDrawable)?.start()
        }
    }

    // ============================================================
    // TEMA DINAMICO
    // ============================================================

    /**
     * Llamado desde SongListActivity cada vez que cambia el acento.
     */
    fun applyAccentColor(color: Int) {
        currentAccentColor = color
        applyAccentColorToViews()
    }

    private fun applyAccentColorToViews() {
        tvAppName.setTextColor(currentAccentColor)

        val iconTint = ColorStateList.valueOf(currentAccentColor)
        btnSearch.imageTintList = iconTint
        btnSort.imageTintList = iconTint
        btnSettings.imageTintList = iconTint

        styleTabBackgrounds()
    }

    /**
     * Reaplica el fondo de la pestaña activa.
     */
    private fun styleTabBackgrounds() {
        val activeTab = if (isPlaylistsTabActive) tabPlaylists else tabSongs
        val inactiveTab = if (isPlaylistsTabActive) tabSongs else tabPlaylists

        activeTab.setBackgroundResource(R.drawable.bg_tab_selected)
        activeTab.backgroundTintList = ColorStateList.valueOf(currentAccentColor)
        // Antes esto era siempre text_primary_light (blanco fijo). Si el
        // acento extraido de la caratula sale claro (casi blanco), el
        // fondo de la pestaña activa y su texto quedaban del mismo tono y
        // la palabra "Canciones"/"Playlists" desaparecia. onColorFor()
        // calcula blanco o negro segun el contraste real del fondo, igual
        // que ya se hizo para el boton de play del Home (HomeController).
        activeTab.setTextColor(PlayerPaletteTheme.onColorFor(currentAccentColor))

        inactiveTab.background = null
        inactiveTab.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_light))
    }

    // ============================================================
    // BUSQUEDA
    // ============================================================

    private fun toggleInlineSearch() {
        isSearchVisible = !isSearchVisible

        if (isSearchVisible) {
            tvAppName.visibility = View.GONE
            ivMascot.visibility = View.GONE
            llInlineSearch.visibility = View.VISIBLE
            btnSort.visibility = View.GONE
            btnSettings.visibility = View.GONE
            btnSearch.setImageResource(R.drawable.ic_close)

            etSearch.requestFocus()

            val imm = activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        } else {
            llInlineSearch.visibility = View.GONE
            tvAppName.visibility = View.VISIBLE
            ivMascot.visibility = View.VISIBLE
            btnSort.visibility = View.VISIBLE
            btnSettings.visibility = View.VISIBLE
            btnSearch.setImageResource(R.drawable.ic_search)

            etSearch.setText("")

            val imm = activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        }
    }

    // ============================================================
    // MENU DE ORDEN
    // ============================================================

    private fun showSortPopup() {
        val popupView = activity.layoutInflater.inflate(R.layout.popup_sort_menu, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.elevation = 16f

        val tvReverse: TextView = popupView.findViewById(R.id.tvSortReverse)
        tvReverse.text = if (isSortReversed()) "Invertir orden ✓" else "Invertir orden"
        tvReverse.setOnClickListener {
            onSortReverseToggled()
            popupWindow.dismiss()
        }

        // Las cinco opciones de orden comparten exactamente el mismo
        // comportamiento (aplicar el tipo y cerrar el popup), asi que se
        // recorren en lugar de repetir el mismo listener cinco veces.
        val sortOptions = listOf(
            R.id.tvSortTitle to SongListActivity.SortType.TITLE,
            R.id.tvSortArtist to SongListActivity.SortType.ARTIST,
            R.id.tvSortDuration to SongListActivity.SortType.DURATION,
            R.id.tvSortDateAdded to SongListActivity.SortType.DATE_ADDED,
            R.id.tvSortMostPlayed to SongListActivity.SortType.MOST_PLAYED
        )
        for ((viewId, sortType) in sortOptions) {
            popupView.findViewById<TextView>(viewId).setOnClickListener {
                onSortSelected(sortType)
                popupWindow.dismiss()
            }
        }

        popupWindow.showAsDropDown(btnSort, -180, 12)
    }

    // ============================================================
    // SWIPE
    // ============================================================

    fun dispatchTouchEvent(ev: MotionEvent) {
        if (isSearchVisible || isHomeActive) return

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
                swipeTracking = true
            }

            MotionEvent.ACTION_UP -> {
                if (!swipeTracking) return
                swipeTracking = false

                val diffX = ev.rawX - swipeDownX
                val diffY = ev.rawY - swipeDownY
                val absX = abs(diffX)
                val absY = abs(diffY)

                // El gesto tiene que ser claramente horizontal.
                if (absX < SWIPE_MIN_DISTANCE) return
                if (absX <= absY * 1.35f) return

                /*
                 * =====================================================
                 * SWIPE IZQUIERDA
                 * =====================================================
                 *
                 * diffX < 0
                 *
                 * PLAYLISTS -> CANCIONES
                 * CANCIONES -> PLAYLISTS
                 *
                 * Pero solo permitimos el segundo caso cuando la
                 * navegación comenzó entrando a Playlists desde Home.
                 */
                if (diffX < 0) {
                    if (!isPlaylistsTabActive && playlistNavigationSession) {
                        selectPlaylistsTab()
                    }

                    // Si estamos en Canciones directamente desde Home,
                    // el swipe queda bloqueado.
                    return
                }

                /*
                 * =====================================================
                 * SWIPE DERECHA
                 * =====================================================
                 *
                 * diffX > 0
                 *
                 * PLAYLISTS -> CANCIONES
                 *
                 * CANCIONES:
                 *     - si venimos de Playlists -> Home
                 *     - si venimos directamente de Home -> Home
                 *
                 * En ambos casos salir de Canciones por la derecha
                 * lleva a Home.
                 */
                if (diffX > 0) {
                    // Ya no existe el paso intermedio Playlists -> Canciones.
                    // Tanto desde Playlists como desde Canciones, el swipe
                    // a la derecha regresa directo a Home.
                    playlistNavigationSession = false
                    onHomeRequested()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                swipeTracking = false
            }
        }
    }

    // ============================================================
    // HOME
    // ============================================================

    /**
     * Marca el contexto actual como Home.
     *
     * Al entrar a Home se reinicia la sesion de navegacion.
     */
    fun setHomeActive(active: Boolean) {
        isHomeActive = active

        if (active) {
            isPlaylistsTabActive = false

            // IMPORTANTE: al regresar a Home termina cualquier sesion
            // anterior de Playlists.
            playlistNavigationSession = false

            rvSongs.animate().cancel()
            rvPlaylists.animate().cancel()
            rvSongs.translationX = 0f
            rvPlaylists.translationX = 0f
            rvSongs.visibility = View.GONE
            rvPlaylists.visibility = View.GONE
            tvEmptyState.visibility = View.GONE

            // En Home no mostramos ninguna pestaña como activa.
            tabSongs.visibility = View.VISIBLE
            tabPlaylists.visibility = View.VISIBLE

            styleTabBackgrounds()
        }
    }

    // ============================================================
    // HOME -> CANCIONES
    // ============================================================

    /**
     * Abre Canciones directamente desde Home.
     *
     * Esta entrada NO permite:
     *
     *     Canciones -> Playlists
     */
    fun openSongsFromHome() {
        isHomeActive = false
        isPlaylistsTabActive = false

        // NUEVA sesion desde Home. Como entramos directamente a Canciones,
        // no existe una Playlists anterior a la que regresar.
        playlistNavigationSession = false

        // Ocultamos completamente Playlists.
        tabSongs.visibility = View.VISIBLE
        tabPlaylists.visibility = View.GONE
        styleTabBackgrounds()

        btnSearch.visibility = View.VISIBLE
        btnSort.visibility = View.VISIBLE
        rvPlaylists.visibility = View.GONE
        rvSongs.visibility = View.VISIBLE

        tvEmptyState.visibility = if (rvSongs.visibility == View.VISIBLE) View.GONE else View.VISIBLE

        onSongsTabSelected()
    }

    // ============================================================
    // HOME -> PLAYLISTS
    // ============================================================

    /**
     * Abre Playlists desde Home.
     *
     * Desde aqui NO se puede ir a Canciones: la pestaña
     * Canciones queda oculta y el swipe a la derecha
     * regresa directo a Home.
     */
    fun openPlaylistsFromHome() {
        isHomeActive = false
        isPlaylistsTabActive = true

        /*
         * IMPORTANTE:
         *
         * Ya no se permite volver a Canciones desde Playlists.
         * La unica forma de llegar a Canciones es regresando
         * a Home y entrando de nuevo por openSongsFromHome().
         */
        playlistNavigationSession = false

        // En Playlists solo mostramos la pestaña Playlists.
        // La pestaña Canciones queda oculta.
        tabSongs.visibility = View.GONE
        tabPlaylists.visibility = View.VISIBLE
        styleTabBackgrounds()

        tvEmptyState.visibility = View.GONE
        closeSearchIfVisible()

        btnSort.visibility = View.VISIBLE
        btnSearch.visibility = View.GONE
        rvSongs.visibility = View.GONE
        rvPlaylists.visibility = View.VISIBLE

        onPlaylistsTabSelected()
    }

    // Cierra la barra de busqueda inline si estaba abierta, dejando los
    // botones/textos del encabezado en su estado normal. Se usa al entrar
    // a Playlists (desde Home o desde Canciones), donde la busqueda no
    // aplica, y tambien al volver a Home (ver HomeNavigationController.
    // showHome()): antes solo se ocultaba btnSearch, pero llInlineSearch
    // se quedaba visible y "flotando" sobre Home si el usuario presionaba
    // Atras mientras tenia la busqueda abierta en Canciones.
    fun closeSearchIfVisible() {
        if (!isSearchVisible) return
        isSearchVisible = false
        llInlineSearch.visibility = View.GONE
        tvAppName.visibility = View.VISIBLE
        ivMascot.visibility = View.VISIBLE
        btnSort.visibility = View.VISIBLE
        btnSettings.visibility = View.VISIBLE
        btnSearch.setImageResource(R.drawable.ic_search)
        etSearch.setText("")
    }

    // ============================================================
    // PLAYLISTS -> CANCIONES
    // ============================================================

    private fun selectSongsTab() {
        // Solo podemos hacer este cambio cuando estamos actualmente en Playlists.
        if (!isPlaylistsTabActive) return

        isHomeActive = false
        isPlaylistsTabActive = false

        /*
         * NO hacemos:
         *
         *     playlistNavigationSession = false
         *
         * porque necesitamos conservar la sesion.
         *
         * Asi:
         *
         *     PLAYLISTS -> CANCIONES
         *
         * permite despues:
         *
         *     CANCIONES -> PLAYLISTS
         */

        // Ocultamos la pestaña Playlists mientras estamos en Canciones.
        tabSongs.visibility = View.VISIBLE
        tabPlaylists.visibility = View.GONE
        styleTabBackgrounds()

        btnSearch.visibility = View.VISIBLE

        onSongsTabSelected()

        // Playlists sale hacia la derecha. Canciones entra desde la izquierda.
        slideTabs(outgoing = rvPlaylists, incoming = rvSongs, toPlaylists = false)
    }

    // ============================================================
    // CANCIONES -> PLAYLISTS
    // ============================================================

    private fun selectPlaylistsTab() {
        // Ya estamos en Playlists.
        if (isPlaylistsTabActive) return

        // SEGURIDAD: si llegamos a Canciones directamente desde Home,
        // no permitimos entrar a Playlists.
        if (!playlistNavigationSession) return

        isHomeActive = false
        isPlaylistsTabActive = true

        // Ahora estamos nuevamente en Playlists, por lo que mostramos ambas pestañas.
        tabSongs.visibility = View.VISIBLE
        tabPlaylists.visibility = View.VISIBLE
        styleTabBackgrounds()

        tvEmptyState.visibility = View.GONE
        closeSearchIfVisible()

        btnSearch.visibility = View.GONE

        onPlaylistsTabSelected()

        // Canciones sale hacia la izquierda. Playlists entra desde la derecha.
        slideTabs(outgoing = rvSongs, incoming = rvPlaylists, toPlaylists = true)
    }

    // ============================================================
    // ANIMACION ENTRE PESTAÑAS
    // ============================================================

    /**
     * Desliza dos vistas como si fueran paginas de un ViewPager.
     *
     * toPlaylists = true:
     *
     *     Playlists entra desde la derecha.
     *     Canciones sale hacia la izquierda.
     *
     * toPlaylists = false:
     *
     *     Canciones entra desde la izquierda.
     *     Playlists sale hacia la derecha.
     */
    private fun slideTabs(outgoing: View, incoming: View, toPlaylists: Boolean) {
        val width = rootLayout.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val widthF = width.toFloat()

        outgoing.animate().cancel()
        incoming.animate().cancel()

        val incomingStartX = if (toPlaylists) widthF else -widthF
        val outgoingEndX = if (toPlaylists) -widthF else widthF

        // Colocamos la pantalla entrante fuera de la pantalla.
        incoming.translationX = incomingStartX
        incoming.visibility = View.VISIBLE

        // Animacion de la pantalla que sale.
        outgoing.animate()
            .translationX(outgoingEndX)
            .setDuration(TAB_SLIDE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.translationX = 0f
            }
            .start()

        // Animacion de la pantalla que entra.
        incoming.animate()
            .translationX(0f)
            .setDuration(TAB_SLIDE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    companion object {
        private const val SWIPE_MIN_DISTANCE = 120
        private const val SWIPE_MIN_VELOCITY = 200
        private const val TAB_SLIDE_DURATION = 300L
    }
}