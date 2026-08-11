package com.learnlayout.mp_3

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.AnimationDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
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

/**
 * Encapsula la barra superior de SongListActivity: nombre de la app y
 * mascota, busqueda inline, popup de orden, boton de ajustes, y el cambio
 * de pestana Canciones/Playlists (por tap en las pestanas o por swipe
 * horizontal), incluyendo la animacion tipo ViewPager entre ambas listas.
 *
 * No sabe nada de como se cargan canciones o playlists: cuando el usuario
 * cambia de contexto (busca, ordena, cambia de pestana) solo avisa via
 * callbacks y deja que la Activity decida que hacer con los datos.
 */
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
    private val onSongsTabSelected: () -> Unit,
    private val onPlaylistsTabSelected: () -> Unit
) {

    var isPlaylistsTabActive: Boolean = false
        private set

    private var isSearchVisible: Boolean = false

    private lateinit var swipeGestureDetector: GestureDetector

    // ---------- Tema dinamico (Material You / PlayerPaletteTheme) ----------
    // Mismo acento que ya sigue el panel del reproductor (extraido de la
    // caratula de la cancion actual). Se aplica al titulo "MP_3", los
    // iconos de buscar/ordenar/config y el fondo de la pestana activa.
    // El color del borde de la cancion sonando en la lista (bg_item_song_playing)
    // NO se toca aqui ni en ningun otro lado: eso queda fijo a proposito.
    // Arranca en spotify_green, el mismo morado que bg_tab_selected ya
    // traia fijo, para que no haya salto visual antes de que llegue el
    // primer acento real.
    private var currentAccentColor: Int = ContextCompat.getColor(activity, R.color.spotify_green)

    fun setup() {
        btnSearch.setOnClickListener {
            toggleInlineSearch()
        }

        btnSort.setOnClickListener {
            showSortPopup()
        }

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

        tabSongs.setOnClickListener {
            selectSongsTab()
        }

        tabPlaylists.setOnClickListener {
            selectPlaylistsTab()
        }

        setupSwipeGesture()

        applyAccentColorToViews()
    }

    fun startMascotAnimation() {
        // .post() asegura que la vista ya este "attachada" a la ventana;
        // si se llama .start() demasiado pronto, la animacion no arranca.
        ivMascot.post {
            (ivMascot.drawable as? AnimationDrawable)?.start()
        }
    }

    fun dispatchTouchEvent(ev: MotionEvent) {
        if (::swipeGestureDetector.isInitialized) {
            swipeGestureDetector.onTouchEvent(ev)
        }
    }

    // ---------- Tema dinamico: aplicacion ----------

    /** Llamado desde SongListActivity cada vez que cambia el acento del panel. */
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

    // Reaplica el fondo de la pestana activa con el acento actual. Se llama
    // tanto al cambiar de pestana como al cambiar de acento, para que
    // ambos casos queden siempre sincronizados.
    private fun styleTabBackgrounds() {
        val activeTab = if (isPlaylistsTabActive) tabPlaylists else tabSongs
        val inactiveTab = if (isPlaylistsTabActive) tabSongs else tabPlaylists

        activeTab.setBackgroundResource(R.drawable.bg_tab_selected)
        activeTab.backgroundTintList = ColorStateList.valueOf(currentAccentColor)
        activeTab.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))

        inactiveTab.background = null
        inactiveTab.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_light))
    }

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

        val tvTitle: TextView = popupView.findViewById(R.id.tvSortTitle)
        val tvArtist: TextView = popupView.findViewById(R.id.tvSortArtist)
        val tvDuration: TextView = popupView.findViewById(R.id.tvSortDuration)
        val tvDateAdded: TextView = popupView.findViewById(R.id.tvSortDateAdded)
        val tvMostPlayed: TextView = popupView.findViewById(R.id.tvSortMostPlayed)

        tvTitle.setOnClickListener {
            onSortSelected(SongListActivity.SortType.TITLE)
            popupWindow.dismiss()
        }
        tvArtist.setOnClickListener {
            onSortSelected(SongListActivity.SortType.ARTIST)
            popupWindow.dismiss()
        }
        tvDuration.setOnClickListener {
            onSortSelected(SongListActivity.SortType.DURATION)
            popupWindow.dismiss()
        }
        tvDateAdded.setOnClickListener {
            onSortSelected(SongListActivity.SortType.DATE_ADDED)
            popupWindow.dismiss()
        }
        tvMostPlayed.setOnClickListener {
            onSortSelected(SongListActivity.SortType.MOST_PLAYED)
            popupWindow.dismiss()
        }

        popupWindow.showAsDropDown(btnSort, -180, 12)
    }

    private fun setupSwipeGesture() {
        swipeGestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || isSearchVisible) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                val isMostlyHorizontal = abs(diffX) > abs(diffY) * 1.5f
                val isFastEnough = abs(velocityX) > SWIPE_MIN_VELOCITY

                if (!isMostlyHorizontal || !isFastEnough) return false

                // El dedo se mueve hacia la izquierda para traer la siguiente
                // pestana (Playlists) desde la derecha; hacia la derecha para
                // regresar (Canciones), como un ViewPager.
                val isSwipeLeft = diffX < -SWIPE_MIN_DISTANCE
                val isSwipeRight = diffX > SWIPE_MIN_DISTANCE

                if (isSwipeLeft && !isPlaylistsTabActive) {
                    selectPlaylistsTab()
                    return true
                }

                if (isSwipeRight && isPlaylistsTabActive) {
                    selectSongsTab()
                    return true
                }

                return false
            }
        })
    }

    private fun selectSongsTab() {
        if (!isPlaylistsTabActive) return

        isPlaylistsTabActive = false
        styleTabBackgrounds()

        btnSearch.visibility = View.VISIBLE

        onSongsTabSelected()

        // toPlaylists = false: Canciones entra desde la izquierda, Playlists
        // sale por la derecha.
        slideTabs(outgoing = rvPlaylists, incoming = rvSongs, toPlaylists = false)
    }

    private fun selectPlaylistsTab() {
        if (isPlaylistsTabActive) return

        isPlaylistsTabActive = true
        styleTabBackgrounds()

        tvEmptyState.visibility = View.GONE

        if (isSearchVisible) {
            isSearchVisible = false
            llInlineSearch.visibility = View.GONE
            tvAppName.visibility = View.VISIBLE
            ivMascot.visibility = View.VISIBLE
            btnSort.visibility = View.VISIBLE
            btnSettings.visibility = View.VISIBLE
            btnSearch.setImageResource(R.drawable.ic_search)
            etSearch.setText("")
        }
        btnSearch.visibility = View.GONE

        onPlaylistsTabSelected()

        // toPlaylists = true: Playlists entra desde la derecha, Canciones
        // sale por la izquierda.
        slideTabs(outgoing = rvSongs, incoming = rvPlaylists, toPlaylists = true)
    }

    /**
     * Desliza dos vistas como si fueran paginas de un ViewPager: la vista
     * "incoming" arranca completamente fuera de pantalla (a la derecha si
     * toPlaylists es true, a la izquierda si es false) y se desliza hasta su
     * posicion normal, mientras "outgoing" se desliza hacia el lado opuesto
     * hasta salir de pantalla, donde se oculta con GONE.
     */
    private fun slideTabs(outgoing: View, incoming: View, toPlaylists: Boolean) {
        val width = rootLayout.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val widthF = width.toFloat()

        outgoing.animate().cancel()
        incoming.animate().cancel()

        val incomingStartX = if (toPlaylists) widthF else -widthF
        val outgoingEndX = if (toPlaylists) -widthF else widthF

        incoming.translationX = incomingStartX
        incoming.visibility = View.VISIBLE

        outgoing.animate()
            .translationX(outgoingEndX)
            .setDuration(TAB_SLIDE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.translationX = 0f
            }
            .start()

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