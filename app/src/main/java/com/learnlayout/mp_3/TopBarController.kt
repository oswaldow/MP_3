package com.learnlayout.mp_3

import android.content.Intent
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

        tabSongs.setBackgroundResource(R.drawable.bg_tab_selected)
        tabSongs.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))
        tabPlaylists.background = null
        tabPlaylists.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_light))

        btnSearch.visibility = View.VISIBLE

        onSongsTabSelected()

        // toPlaylists = false: Canciones entra desde la izquierda, Playlists
        // sale por la derecha.
        slideTabs(outgoing = rvPlaylists, incoming = rvSongs, toPlaylists = false)
    }

    private fun selectPlaylistsTab() {
        if (isPlaylistsTabActive) return

        isPlaylistsTabActive = true

        tabPlaylists.setBackgroundResource(R.drawable.bg_tab_selected)
        tabPlaylists.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))
        tabSongs.background = null
        tabSongs.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_light))

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