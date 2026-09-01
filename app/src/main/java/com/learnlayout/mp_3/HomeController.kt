package com.learnlayout.mp_3

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class HomeController(
    private val context: Context,
    private val root: View,
    private val backgroundTarget: View,
    private val getAllSongs: () -> List<Song>,
    private val getCurrentSong: () -> Song?,
    private val isPlaying: () -> Boolean,
    private val onPlaySong: (Song) -> Unit,
    private val onOpenSongs: () -> Unit,
    // Paneles "liquid glass" que viven FUERA del arbol de vistas del Home
    // (por ejemplo groupMini, el mini reproductor que se ve en todas las
    // pestañas) pero que deben fotografiar el mismo fondo animado y
    // refrescarse junto con los del Home cada vez que ese fondo cambia de
    // color. Vacio por defecto para no romper otros usos de HomeController.
    private val additionalGlassPanels: List<LiquidGlassView> = emptyList()
) {

    // ============================================================
    // VIEWS DEL HOME
    // ============================================================

    private val tvWelcome: TextView =
        root.findViewById(R.id.tvHomeWelcome)

    private val tvSummary: TextView =
        root.findViewById(R.id.tvHomeSummary)

    private val heroContainer: View =
        root.findViewById(R.id.homeHeroCard)

    private val ivHeroArt: ImageView =
        root.findViewById(R.id.ivHomeHeroArt)

    private val tvHeroEyebrow: TextView =
        root.findViewById(R.id.tvHomeHeroEyebrow)

    private val tvHeroTitle: TextView =
        root.findViewById(R.id.tvHomeHeroTitle)

    private val tvHeroArtist: TextView =
        root.findViewById(R.id.tvHomeHeroArtist)

    private val recentSection: View =
        root.findViewById(R.id.homeRecentSection)

    private val recentContainer: LinearLayout =
        root.findViewById(R.id.homeRecentContainer)

    private val mostSection: View =
        root.findViewById(R.id.homeMostSection)

    private val mostContainer: LinearLayout =
        root.findViewById(R.id.homeMostContainer)

    private val addedSection: View =
        root.findViewById(R.id.homeAddedSection)

    private val addedContainer: LinearLayout =
        root.findViewById(R.id.homeAddedContainer)

    private val tvFavoritesCount: TextView =
        root.findViewById(R.id.tvHomeFavoritesCount)

    private val tvRecentCount: TextView =
        root.findViewById(R.id.tvHomeRecentCount)

    private val tvMostCount: TextView =
        root.findViewById(R.id.tvHomeMostCount)


    // ============================================================
    // PANELES "LIQUID GLASS" (Accesos rapidos)
    // ============================================================

    /**
     * El banner de hero ("Continuar escuchando") + los paneles de
     * "Accesos rapidos" del Home. Cada uno se "fotografia" contra
     * [backgroundTarget] (el mismo fondo animado de degradado +
     * destellos) para simular vidrio esmerilado. Ver [LiquidGlassView].
     */
    private val glassPanels: List<LiquidGlassView> =
        listOf(
            R.id.homeHeroCard,
            R.id.btnHomeFavorites,
            R.id.btnHomeRecent,
            R.id.btnHomeSongs,
            R.id.btnHomePlaylists,
            R.id.btnHomeMostPlayed
        ).map { id ->
            root.findViewById<LiquidGlassView>(id)
        } + additionalGlassPanels

    init {
        glassPanels.forEach { panel ->
            panel.attachBackdrop(backgroundTarget)
        }
    }


    // ============================================================
    // CANCION DEL HERO
    // ============================================================

    private var heroSong: Song? = null


    // ============================================================
    // TEMA DINAMICO
    // ============================================================

    /**
     * Llamado desde SongListActivity via AppAccentColor cada vez que
     * cambia el color extraido de la caratula de la cancion actual (o
     * vuelve al fallback morado si no hay caratula). Antes estos dos
     * elementos se quedaban fijos en R.color.spotify_green sin importar
     * la caratula.
     */
    fun applyAccentColor(color: Int) {
        tvHeroEyebrow.setTextColor(color)
    }


    // ============================================================
    // FONDO DINAMICO (Material You con luces de fondo)
    // ============================================================

    /**
     * Controlador COMPARTIDO del fondo animado (degradado + destellos
     * de luz), usado tambien por el resto de las pantallas de la app
     * (ver [AmbientBackgroundController]). Antes esta logica vivia
     * duplicada aqui mismo; ahora el Home es simplemente el primer
     * consumidor de un controlador reutilizable.
     *
     * [backgroundTarget] se recibe explicito desde SongListActivity (en
     * vez de inferirlo caminando el arbol de vistas con root.parent, como
     * antes) para que el fondo siga cubriendo TODA la pantalla de la
     * actividad (Home, Canciones y Playlists comparten el mismo
     * contenedor) sin importar cuantos niveles de layout intermedios
     * (por ejemplo el SwipeRefreshLayout del gesto de "tirar para
     * abajo") terminen quedando entre homeView y la raiz. Depender del
     * padre inmediato era fragil: cualquier envoltorio nuevo alrededor
     * de homeView rompia el fondo sin avisar.
     *
     * onBackgroundUpdated refresca los paneles liquid glass cada vez
     * que termina la animacion de cambio de color, para que el vidrio
     * quede en sintonia con el nuevo tono de la caratula.
     */
    private val ambientBackground: AmbientBackgroundController by lazy {
        AmbientBackgroundController(
            context = context,
            targetView = backgroundTarget,
            onBackgroundUpdated = { refreshGlassPanels() }
        )
    }

    /**
     * Actualiza SOLO el fondo animado (y en consecuencia los paneles
     * liquid glass, incluido groupMini via additionalGlassPanels) para
     * reflejar [song], sin tocar el resto del contenido del Home
     * (textos, filas de canciones, contadores, etc).
     *
     * A diferencia de [refresh], debe poder llamarse aunque el Home NO
     * este visible: groupMini (el mini reproductor) se ve en las tres
     * pestañas, asi que necesita que el fondo se mantenga al dia con la
     * cancion sonando sin importar cual pestaña este activa. Se llama
     * desde SongListActivity.showMiniPlayer() cada vez que se muestra o
     * actualiza el mini player (cambio de cancion, reconexion al
     * servicio al reabrir la app, etc), que es justo el caso que antes
     * se perdia: al reconectar con el servicio y restaurar la cancion
     * que ya estaba sonando, se llamaba a showMiniPlayer() sin pasar
     * por refresh(), asi que el fondo se quedaba en el degradado neutro
     * por defecto y groupMini fotografiaba ese neutro en vez del color
     * real de la cancion.
     */
    fun updateAmbientBackground(song: Song?) {
        ambientBackground.updateForSong(song)
    }


    // ============================================================
    // REFRESH
    // ============================================================

    fun refresh() {

        val songs = getAllSongs()
        val current = getCurrentSong()

        val recentIds =
            PlayCountRepository.getRecentlyPlayedSongIds(
                context,
                20
            )

        val mostIds =
            PlayCountRepository.getMostPlayedSongIds(
                context,
                20
            )

        val favoriteIds =
            PlaylistRepository.getPlaylistById(
                context,
                PlaylistRepository.FAVORITES_PLAYLIST_ID
            )?.songIds.orEmpty()


        // --------------------------------------------------------
        // TEXTO SUPERIOR
        // --------------------------------------------------------

        tvWelcome.text = greeting()

        tvSummary.text = when {

            songs.isEmpty() ->
                "Agrega música a tu biblioteca para empezar"

            songs.size == 1 ->
                "1 canción en tu biblioteca"

            else ->
                "${songs.size} canciones en tu biblioteca"
        }


        // --------------------------------------------------------
        // CANCIONES
        // --------------------------------------------------------

        // IMPORTANTE: recentIds/mostIds/favoriteIds pueden contener
        // songId "huerfanos" que ya no corresponden a ninguna cancion
        // actual (ver SongIdMigrator: MediaStore les asigna un _ID
        // nuevo a los archivos reescritos, y las tablas que guardaban
        // el _ID viejo quedan apuntando a nada). Filtramos contra byId
        // ANTES de contar, para que el numero que se ve en el Home
        // coincida con lo que el usuario realmente ve al entrar a cada
        // lista (PlaylistDetailActivity hace exactamente este mismo
        // filtro). Antes se mostraba favorites.size/recentIds.size/
        // mostIds.size "en bruto", sin filtrar, por lo que el Home
        // podia anunciar mas canciones de las que la lista real tenia.

        val byId =
            songs.associateBy { it.id }

        val recentSongs =
            recentIds.mapNotNull { byId[it] }

        val mostSongs =
            mostIds.mapNotNull { byId[it] }

        val favoriteSongs =
            favoriteIds.mapNotNull { byId[it] }

        val addedSongs =
            songs
                .sortedByDescending { it.dateAdded }
                .take(12)


        // --------------------------------------------------------
        // CONTADORES
        // --------------------------------------------------------

        tvFavoritesCount.text =
            favoriteSongs.size.toString()

        tvRecentCount.text =
            recentSongs.size.toString()

        tvMostCount.text =
            mostSongs.size.toString()


        // --------------------------------------------------------
        // HERO
        // --------------------------------------------------------

        val hero =
            current
                ?: recentSongs.firstOrNull()
                ?: addedSongs.firstOrNull()

        configureHero(hero)


        // --------------------------------------------------------
        // RECIENTES
        // --------------------------------------------------------

        populateSongRow(
            recentContainer,
            recentSongs.take(20)
        )

        recentSection.visibility =
            if (recentSongs.isNotEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }


        // --------------------------------------------------------
        // MAS ESCUCHADAS
        // --------------------------------------------------------

        populateSongRow(
            mostContainer,
            mostSongs.take(8)
        )

        mostSection.visibility =
            if (mostSongs.isNotEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }


        // --------------------------------------------------------
        // AGREGADAS RECIENTEMENTE
        // --------------------------------------------------------

        populateSongRow(
            addedContainer,
            addedSongs.take(8)
        )

        addedSection.visibility =
            if (addedSongs.isNotEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }


        // --------------------------------------------------------
        // FONDO DINAMICO
        // --------------------------------------------------------

        // Usamos "hero" (no "current") a proposito: hero es la MISMA
        // cancion que ya se muestra en la tarjeta principal del Home
        // (reproduciendose, o si no hay nada sonando, la mas reciente
        // o la ultima agregada). Antes se usaba "current", que es null
        // cuando no hay reproduccion activa, asi que el fondo dinamico
        // nunca reaccionaba a las canciones mostradas en el Home, solo
        // a la que sonaba.
        ambientBackground.updateForSong(hero)
    }


    // ============================================================
    // PANELES LIQUID GLASS
    // ============================================================

    /**
     * Vuelve a "fotografiar" el fondo animado en cada panel de
     * Accesos rapidos. Se llama cuando termina de cambiar el color de
     * fondo (ver [ambientBackground]).
     */
    private fun refreshGlassPanels() {
        glassPanels.forEach { panel ->
            panel.refreshGlass()
        }
    }


    // ============================================================
    // HERO
    // ============================================================

    private fun configureHero(song: Song?) {

        heroSong = song

        if (song == null) {

            heroContainer.visibility =
                View.VISIBLE

            tvHeroEyebrow.text =
                "TU BIBLIOTECA"

            tvHeroTitle.text =
                "Empieza a escuchar"

            tvHeroArtist.text =
                "Toca para ver tus canciones"

            ivHeroArt.setImageResource(
                R.drawable.ic_music_note
            )

            ivHeroArt.setPadding(
                dp(32),
                dp(32),
                dp(32),
                dp(32)
            )

            heroContainer.setOnClickListener {
                onOpenSongs()
            }

            return
        }


        tvHeroEyebrow.text =
            if (getCurrentSong()?.id == song.id) {
                "SONANDO AHORA"
            } else {
                "CONTINUAR ESCUCHANDO"
            }


        tvHeroTitle.text =
            song.title

        tvHeroArtist.text =
            song.artist


        heroContainer.setOnClickListener {
            onPlaySong(song)
        }


        loadCover(
            song,
            ivHeroArt,
            180
        )
    }


    // ============================================================
    // FILAS DE CANCIONES
    // ============================================================

    private fun populateSongRow(
        container: LinearLayout,
        songs: List<Song>
    ) {

        container.removeAllViews()

        songs.forEach { song ->

            val item =
                LayoutInflater
                    .from(context)
                    .inflate(
                        R.layout.item_home_song,
                        container,
                        false
                    )


            val iv =
                item.findViewById<ImageView>(
                    R.id.ivHomeSongArt
                )

            val title =
                item.findViewById<TextView>(
                    R.id.tvHomeSongTitle
                )

            val artist =
                item.findViewById<TextView>(
                    R.id.tvHomeSongArtist
                )


            title.text =
                song.title

            artist.text =
                song.artist


            item.setOnClickListener {
                onPlaySong(song)
            }


            loadCover(
                song,
                iv,
                140
            )


            container.addView(item)
        }
    }


    // ============================================================
    // CARGAR CARATULA
    // ============================================================

    private fun loadCover(
        song: Song,
        imageView: ImageView,
        targetDp: Int
    ) {

        imageView.setImageResource(
            R.drawable.ic_music_note
        )

        imageView.setPadding(
            dp(26),
            dp(26),
            dp(26),
            dp(26)
        )

        imageView.imageTintList =
            ContextCompat.getColorStateList(
                context,
                R.color.spotify_gray
            )


        val cached =
            AlbumArtRepository.getCachedCover(song)


        if (cached != null) {

            applyBitmap(
                imageView,
                cached
            )

            return
        }


        AlbumArtRepository.loadCover(
            context,
            song,
            object : AlbumArtRepository.Callback {

                override fun onCoverReady(
                    bitmap: Bitmap
                ) {

                    applyBitmap(
                        imageView,
                        bitmap
                    )
                }
            }
        )
    }


    // ============================================================
    // APLICAR BITMAP
    // ============================================================

    private fun applyBitmap(
        imageView: ImageView,
        bitmap: Bitmap
    ) {

        imageView.setPadding(
            0,
            0,
            0,
            0
        )

        imageView.imageTintList = null

        imageView.scaleType =
            ImageView.ScaleType.CENTER_CROP

        imageView.setImageBitmap(bitmap)
    }


    // ============================================================
    // SALUDO
    // ============================================================

    private fun greeting(): String {

        return when (
            java.util.Calendar
                .getInstance()
                .get(
                    java.util.Calendar.HOUR_OF_DAY
                )
        ) {

            in 5..11 ->
                "Buenos días"

            in 12..18 ->
                "Buenas tardes"

            else ->
                "Buenas noches"
        }
    }


    // ============================================================
    // DP
    // ============================================================

    private fun dp(
        value: Int
    ): Int {

        return (
                value *
                        context.resources
                            .displayMetrics
                            .density
                ).toInt()
    }
}