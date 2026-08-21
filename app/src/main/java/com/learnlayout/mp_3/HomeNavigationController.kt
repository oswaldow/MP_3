package com.learnlayout.mp_3

import android.view.View

/**
 * Coordina únicamente la navegación visual entre Home, Canciones y Playlists.
 *
 * No controla reproducción, colas ni datos de la biblioteca.
 */
class HomeNavigationController(
    private val homeView: View,
    private val tabSelector: View,
    private val rvSongs: View,
    private val rvPlaylists: View,
    private val emptyState: View,
    private val btnSearch: View,
    private val btnSort: View,
    private val btnSettings: View,
    private val topBarController: TopBarController,
    private val onHomeShown: () -> Unit
) {

    fun showHome() {
        // Cancelamos cualquier animación pendiente de cambio de pestaña para
        // evitar que una lista vuelva a aparecer después de entrar a Home.
        rvSongs.animate().cancel()
        rvPlaylists.animate().cancel()

        rvSongs.translationX = 0f
        rvPlaylists.translationX = 0f

        rvSongs.visibility = View.GONE
        rvPlaylists.visibility = View.GONE
        emptyState.visibility = View.GONE

        // Si el usuario venia de Canciones con la busqueda abierta (por
        // ejemplo, presiono Atras a mitad de una busqueda), la cerramos
        // aqui. Antes solo se ocultaba btnSearch mas abajo, pero la caja
        // de busqueda (llInlineSearch) en si no se escondia, asi que se
        // quedaba flotando encima de Home.
        topBarController.closeSearchIfVisible()

        topBarController.setHomeActive(true)

        homeView.visibility = View.VISIBLE
        homeView.bringToFront()

        tabSelector.visibility = View.GONE
        btnSearch.visibility = View.GONE
        btnSort.visibility = View.GONE
        btnSettings.visibility = View.VISIBLE

        onHomeShown()
    }

    fun showSongs() {
        homeView.visibility = View.GONE
        tabSelector.visibility = View.VISIBLE
        topBarController.openSongsFromHome()
    }

    fun showPlaylists() {
        homeView.visibility = View.GONE
        tabSelector.visibility = View.VISIBLE
        topBarController.openPlaylistsFromHome()
    }
}