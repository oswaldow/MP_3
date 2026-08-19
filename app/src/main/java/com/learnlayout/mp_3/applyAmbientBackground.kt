package com.learnlayout.mp_3

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/**
 * Fondo dinamico "Material You con luces de fondo": un degradado oscuro
 * de 3 tonos (extraidos de la caratula de una cancion) mas una capa de
 * destellos animados encima (ver [HomeGlowSparkleDrawable]).
 *
 * Esta clase es una extraccion 1:1 de la logica que antes vivia solo en
 * HomeController, para que CUALQUIER pantalla de la app (Ecualizador,
 * Letras, Playlist, Ajustes, Bluetooth, etc.) pueda tener el mismo
 * fondo vivo que el Home, apuntando a su propia vista raiz.
 *
 * Uso tipico dentro de una Activity:
 *
 *   private val ambientBackground: AmbientBackgroundController by lazy {
 *       AmbientBackgroundController(this, rootLayout)
 *   }
 *
 *   ambientBackground.updateForSong(song) // cuando se conoce la cancion
 *   ambientBackground.updateForSong(null) // para volver al fondo neutro
 */
class AmbientBackgroundController(
    private val context: Context,
    private val targetView: View
) {

    /** ID de la cancion que actualmente controla el fondo. */
    private var boundSongId: Long? = null

    /**
     * Capa de base: degradado vertical oscuro (arriba -> abajo). Sirve
     * de piso para que se lea bien el texto y para que los destellos
     * tengan contra que resaltar.
     */
    private val baseGradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                defaultTop(),
                defaultMiddle(),
                defaultBottom()
            )
        )

    /**
     * Capa de destellos animados (chispas de luz tipo "fuegos
     * artificiales"), con el color vivo de la caratula actual.
     */
    private val glowSparkleDrawable =
        HomeGlowSparkleDrawable(
            density = context.resources.displayMetrics.density
        )

    /**
     * Union de ambas capas. El orden importa: la primera capa queda
     * ABAJO, la ultima queda ARRIBA. Por eso los destellos van
     * despues de la base.
     */
    private val backgroundDrawable =
        LayerDrawable(
            arrayOf(
                baseGradientDrawable,
                glowSparkleDrawable
            )
        )

    init {
        targetView.background = backgroundDrawable
    }


    // ============================================================
    // API PUBLICA
    // ============================================================

    /**
     * Actualiza el fondo para reflejar [song]: extrae el color vivo de
     * su caratula (Palette) y anima el degradado + prende los
     * destellos con ese color. Si [song] es null, vuelve al degradado
     * neutro por defecto y apaga los destellos.
     */
    fun updateForSong(song: Song?) {

        if (song == null) {
            boundSongId = null
            glowSparkleDrawable.setActive(false)
            animateTo(defaultTop(), defaultMiddle(), defaultBottom())
            return
        }

        if (boundSongId == song.id) {
            return
        }

        boundSongId = song.id

        val cached = AlbumArtRepository.getCachedCover(song)

        if (cached != null) {
            applyFromBitmap(song.id, cached)
            return
        }

        AlbumArtRepository.loadCover(
            context,
            song,
            object : AlbumArtRepository.Callback {
                override fun onCoverReady(bitmap: Bitmap) {
                    // Si mientras se cargaba la caratula ya se pidio
                    // otra cancion, ignoramos este resultado.
                    if (boundSongId != song.id) return
                    applyFromBitmap(song.id, bitmap)
                }
            }
        )
    }


    // ============================================================
    // EXTRAER COLOR DE LA CARATULA
    // ============================================================

    private fun applyFromBitmap(songId: Long, bitmap: Bitmap) {

        if (boundSongId != songId) {
            return
        }

        Palette
            .from(bitmap)
            .clearFilters()
            .generate { palette ->

                if (boundSongId != songId) {
                    return@generate
                }

                // Para los destellos preferimos los swatches MAS VIVOS
                // (vibrant/lightVibrant) en vez de los oscuros, porque
                // es justo esa saturacion la que hace que se note el
                // chispazo. Si la caratula no tiene un tono vivo,
                // caemos en los swatches oscuros/dominantes.
                val swatch =
                    palette?.vibrantSwatch
                        ?: palette?.lightVibrantSwatch
                        ?: palette?.darkVibrantSwatch
                        ?: palette?.mutedSwatch
                        ?: palette?.dominantSwatch

                if (swatch == null) {
                    glowSparkleDrawable.setActive(false)
                    animateTo(defaultTop(), defaultMiddle(), defaultBottom())
                    return@generate
                }

                val originalColor = swatch.rgb

                val topColor = darken(originalColor, 0.42f)
                val middleColor = darken(originalColor, 0.20f)
                val bottomColor =
                    ColorUtils.blendARGB(originalColor, Color.BLACK, 0.90f)

                // Destellos: usan el color original de la caratula.
                // HomeGlowSparkleDrawable lo convierte en algo
                // vivo/luminoso para cada chispa.
                glowSparkleDrawable.setAccentColor(originalColor)
                glowSparkleDrawable.setActive(true)

                animateTo(topColor, middleColor, bottomColor)
            }
    }


    // ============================================================
    // ANIMAR DEGRADADO DE BASE
    // ============================================================

    private fun animateTo(targetTop: Int, targetMiddle: Int, targetBottom: Int) {

        if (targetView.background !== backgroundDrawable) {
            targetView.background = backgroundDrawable
        }

        val currentColors =
            baseGradientDrawable.colors
                ?: intArrayOf(defaultTop(), defaultMiddle(), defaultBottom())

        val startTop = currentColors.getOrNull(0) ?: defaultTop()
        val startMiddle = currentColors.getOrNull(1) ?: defaultMiddle()
        val startBottom = currentColors.getOrNull(2) ?: defaultBottom()

        ValueAnimator
            .ofFloat(0f, 1f)
            .apply {

                duration = 600L

                addUpdateListener { animator ->

                    val fraction = animator.animatedFraction

                    val top =
                        ArgbEvaluator().evaluate(fraction, startTop, targetTop) as Int

                    val middle =
                        ArgbEvaluator().evaluate(fraction, startMiddle, targetMiddle) as Int

                    val bottom =
                        ArgbEvaluator().evaluate(fraction, startBottom, targetBottom) as Int

                    baseGradientDrawable.colors = intArrayOf(top, middle, bottom)
                }

                start()
            }
    }


    // ============================================================
    // OSCURECER COLOR
    // ============================================================

    private fun darken(color: Int, factor: Float): Int {

        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)

        // Reducimos ligeramente la saturacion.
        hsl[1] = (hsl[1] * 0.95f).coerceIn(0f, 1f)

        // Reducimos la luminosidad. El limite evita que el fondo sea
        // demasiado brillante.
        hsl[2] = (hsl[2] * factor).coerceIn(0f, 0.32f)

        return ColorUtils.HSLToColor(hsl)
    }


    // ============================================================
    // COLORES POR DEFECTO
    // ============================================================

    private fun defaultTop(): Int = Color.rgb(20, 24, 32)

    private fun defaultMiddle(): Int = Color.rgb(14, 16, 22)

    private fun defaultBottom(): Int = Color.rgb(8, 8, 10)
}