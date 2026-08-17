package com.learnlayout.mp_3

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

class HomeController(
    private val context: Context,
    private val root: View,
    private val getAllSongs: () -> List<Song>,
    private val getCurrentSong: () -> Song?,
    private val isPlaying: () -> Boolean,
    private val onPlaySong: (Song) -> Unit,
    private val onOpenSongs: () -> Unit
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

    private val btnHeroPlay: ImageButton =
        root.findViewById(R.id.btnHomeHeroPlay)

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
    // CANCION DEL HERO
    // ============================================================

    private var heroSong: Song? = null


    // ============================================================
    // FONDO DINAMICO
    // ============================================================

    /**
     * ID de la cancion que actualmente controla el fondo.
     */
    private var backgroundSongId: Long? = null

    /**
     * Capa de base: degradado vertical oscuro (arriba -> abajo).
     * Sirve de piso para que se lea bien el texto y para que los
     * destellos tengan contra que resaltar.
     */
    private val baseGradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                defaultBackgroundTop(),
                defaultBackgroundMiddle(),
                defaultBackgroundBottom()
            )
        )

    /**
     * Capa de destellos animados (chispas de luz tipo "fuegos
     * artificiales"), con el color vivo de la caratula actual.
     * Ver [HomeGlowSparkleDrawable].
     */
    private val glowSparkleDrawable =
        HomeGlowSparkleDrawable(
            density = context.resources.displayMetrics.density
        )

    /**
     * Union de ambas capas. Esto es lo que se aplica como fondo
     * de toda la pantalla (fullScreenRoot).
     *
     * El orden importa: la primera capa queda ABAJO, la ultima
     * queda ARRIBA. Por eso los destellos van despues de la base.
     */
    private val homeBackgroundDrawable =
        LayerDrawable(
            arrayOf(
                baseGradientDrawable,
                glowSparkleDrawable
            )
        )


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

        val favorites =
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
        // CONTADORES
        // --------------------------------------------------------

        tvFavoritesCount.text =
            favorites.size.toString()

        tvRecentCount.text =
            recentIds.size.toString()

        tvMostCount.text =
            mostIds.size.toString()


        // --------------------------------------------------------
        // CANCIONES
        // --------------------------------------------------------

        val byId =
            songs.associateBy { it.id }

        val recentSongs =
            recentIds.mapNotNull { byId[it] }

        val mostSongs =
            mostIds.mapNotNull { byId[it] }

        val addedSongs =
            songs
                .sortedByDescending { it.dateAdded }
                .take(12)


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
        applyDynamicBackground(hero)
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

            btnHeroPlay.setImageResource(
                R.drawable.ic_queue_music
            )

            heroContainer.setOnClickListener {
                onOpenSongs()
            }

            btnHeroPlay.setOnClickListener {
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


        btnHeroPlay.setImageResource(
            if (
                getCurrentSong()?.id == song.id &&
                isPlaying()
            ) {
                R.drawable.ic_pause
            } else {
                R.drawable.ic_play_arrow
            }
        )


        heroContainer.setOnClickListener {
            onPlaySong(song)
        }

        btnHeroPlay.setOnClickListener {
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
    // FONDO DINAMICO
    // ============================================================

    private fun applyDynamicBackground(
        song: Song?
    ) {

        // --------------------------------------------------------
        // SIN CANCION ACTUAL
        // --------------------------------------------------------

        if (song == null) {

            backgroundSongId = null

            glowSparkleDrawable.setActive(false)

            animateBackgroundTo(
                defaultBackgroundTop(),
                defaultBackgroundMiddle(),
                defaultBackgroundBottom()
            )

            return
        }


        // --------------------------------------------------------
        // NO REPETIR EL MISMO CAMBIO
        // --------------------------------------------------------

        if (backgroundSongId == song.id) {
            return
        }

        backgroundSongId =
            song.id


        // --------------------------------------------------------
        // BUSCAR CARATULA EN CACHE
        // --------------------------------------------------------

        val cached =
            AlbumArtRepository.getCachedCover(song)


        if (cached != null) {

            updateBackgroundFromBitmap(
                song.id,
                cached
            )

            return
        }


        // --------------------------------------------------------
        // CARGAR CARATULA
        // --------------------------------------------------------

        AlbumArtRepository.loadCover(
            context,
            song,
            object : AlbumArtRepository.Callback {

                override fun onCoverReady(
                    bitmap: Bitmap
                ) {

                    /*
                     * Si el usuario ya cambio de cancion,
                     * ignoramos esta caratula.
                     */
                    if (backgroundSongId != song.id) {
                        return
                    }

                    updateBackgroundFromBitmap(
                        song.id,
                        bitmap
                    )
                }
            }
        )
    }


    // ============================================================
    // EXTRAER COLOR DE LA CARATULA
    // ============================================================

    private fun updateBackgroundFromBitmap(
        songId: Long,
        bitmap: Bitmap
    ) {

        if (backgroundSongId != songId) {
            return
        }


        Palette
            .from(bitmap)
            .clearFilters()
            .generate { palette ->

                /*
                 * Palette puede ser nullable.
                 * Por eso usamos ?. en todas las propiedades.
                 *
                 * Para los destellos preferimos los swatches MAS
                 * VIVOS (vibrant/lightVibrant) en vez de los oscuros,
                 * porque es justo esa saturacion la que hace que se
                 * note el chispazo, igual que en el reproductor del
                 * sistema. Si la caratula no tiene un tono vivo (por
                 * ejemplo, una portada en blanco y negro), caemos en
                 * los swatches oscuros/dominantes.
                 */

                val swatch =
                    palette?.vibrantSwatch
                        ?: palette?.lightVibrantSwatch
                        ?: palette?.darkVibrantSwatch
                        ?: palette?.mutedSwatch
                        ?: palette?.dominantSwatch


                // ------------------------------------------------
                // NO HAY COLOR
                // ------------------------------------------------

                if (swatch == null) {

                    glowSparkleDrawable.setActive(false)

                    animateBackgroundTo(
                        defaultBackgroundTop(),
                        defaultBackgroundMiddle(),
                        defaultBackgroundBottom()
                    )

                    return@generate
                }


                // ------------------------------------------------
                // COLOR ENCONTRADO
                // ------------------------------------------------

                val originalColor =
                    swatch.rgb


                /*
                 * Parte superior de la base (oscura, con matiz).
                 */
                val topColor =
                    darkenForBackground(
                        originalColor,
                        0.42f
                    )


                /*
                 * Parte central de la base.
                 */
                val middleColor =
                    darkenForBackground(
                        originalColor,
                        0.20f
                    )


                /*
                 * Parte inferior de la base: casi negro.
                 */
                val bottomColor =
                    ColorUtils.blendARGB(
                        originalColor,
                        Color.BLACK,
                        0.90f
                    )


                /*
                 * Destellos: usan el color original de la caratula.
                 * HomeGlowSparkleDrawable se encarga de convertirlo
                 * en algo vivo/luminoso para cada chispa.
                 */
                glowSparkleDrawable.setAccentColor(originalColor)
                glowSparkleDrawable.setActive(true)


                animateBackgroundTo(
                    topColor,
                    middleColor,
                    bottomColor
                )
            }
    }


    // ============================================================
    // ANIMAR DEGRADADO DE BASE
    // ============================================================

    private fun animateBackgroundTo(
        targetTop: Int,
        targetMiddle: Int,
        targetBottom: Int
    ) {

        /*
         * root es homeView.
         *
         * homeView está dentro de rootSongListLayout.
         *
         * Por eso obtenemos su padre para colocar el fondo
         * en TODA la pantalla de la actividad.
         */
        val fullScreenRoot =
            root.parent as? View
                ?: root


        // --------------------------------------------------------
        // COLORES ACTUALES
        // --------------------------------------------------------

        val currentColors =
            baseGradientDrawable.colors
                ?: intArrayOf(
                    defaultBackgroundTop(),
                    defaultBackgroundMiddle(),
                    defaultBackgroundBottom()
                )


        val startTop =
            currentColors.getOrNull(0)
                ?: defaultBackgroundTop()

        val startMiddle =
            currentColors.getOrNull(1)
                ?: defaultBackgroundMiddle()

        val startBottom =
            currentColors.getOrNull(2)
                ?: defaultBackgroundBottom()


        // --------------------------------------------------------
        // APLICAR AL CONTENEDOR COMPLETO
        // --------------------------------------------------------

        if (
            fullScreenRoot.background !==
            homeBackgroundDrawable
        ) {

            fullScreenRoot.background =
                homeBackgroundDrawable
        }


        // --------------------------------------------------------
        // ANIMACION
        // --------------------------------------------------------

        ValueAnimator
            .ofFloat(0f, 1f)
            .apply {

                duration = 600L

                addUpdateListener { animator ->

                    val fraction =
                        animator.animatedFraction


                    val top =
                        ArgbEvaluator().evaluate(
                            fraction,
                            startTop,
                            targetTop
                        ) as Int


                    val middle =
                        ArgbEvaluator().evaluate(
                            fraction,
                            startMiddle,
                            targetMiddle
                        ) as Int


                    val bottom =
                        ArgbEvaluator().evaluate(
                            fraction,
                            startBottom,
                            targetBottom
                        ) as Int


                    baseGradientDrawable.colors =
                        intArrayOf(
                            top,
                            middle,
                            bottom
                        )
                }

                start()
            }
    }


    // ============================================================
    // OSCURECER COLOR
    // ============================================================

    private fun darkenForBackground(
        color: Int,
        factor: Float
    ): Int {

        val hsl =
            FloatArray(3)


        ColorUtils.colorToHSL(
            color,
            hsl
        )


        /*
         * Reducimos ligeramente la saturacion.
         */
        hsl[1] =
            (hsl[1] * 0.95f)
                .coerceIn(0f, 1f)


        /*
         * Reducimos la luminosidad.
         *
         * El limite evita que el fondo sea demasiado brillante.
         */
        hsl[2] =
            (hsl[2] * factor)
                .coerceIn(0f, 0.32f)


        return ColorUtils.HSLToColor(hsl)
    }


    // ============================================================
    // COLORES POR DEFECTO
    // ============================================================

    private fun defaultBackgroundTop(): Int {

        return Color.rgb(
            20,
            24,
            32
        )
    }


    private fun defaultBackgroundMiddle(): Int {

        return Color.rgb(
            14,
            16,
            22
        )
    }


    private fun defaultBackgroundBottom(): Int {

        return Color.rgb(
            8,
            8,
            10
        )
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