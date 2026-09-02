package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/**
 * Fondo ESTATICO (sin animacion de degradado ni destellos, a diferencia
 * de [AmbientBackgroundController]) para la pantalla de la cola de
 * reproduccion: un degradado vertical de 3 tonos extraidos de la
 * caratula de la cancion actual, pensado unicamente como "backdrop" que
 * [LiquidGlassView] fotografia y difumina para el efecto liquid glass
 * del panel completo de la cola.
 *
 * A diferencia del fondo del Home/Equalizer/Lyrics, aca no hace falta
 * ni la capa de destellos animados ni la transicion animada entre
 * colores: la cola se abre y se cierra rapido, y el vidrio ya toma una
 * unica foto congelada del fondo (ver LiquidGlassView.refreshGlass()),
 * asi que animar el degradado por debajo no aportaria nada visible.
 *
 * [onBackgroundApplied] se dispara cada vez que los colores quedan
 * fijados (incluida la resolucion asincronica de Palette), para que
 * quien lo use pueda pedirle a su LiquidGlassView que vuelva a
 * fotografiar el fondo (ver QueueSheetController).
 */
class QueueGlassBackgroundController(
    private val context: Context,
    private val targetView: View,
    private val onBackgroundApplied: (() -> Unit)? = null
) {

    /** ID de la cancion que actualmente controla el fondo. */
    private var boundSongId: Long? = null

    private val gradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(defaultTop(), defaultMiddle(), defaultBottom())
    )

    init {
        targetView.background = gradientDrawable
    }


    // ============================================================
    // API PUBLICA
    // ============================================================

    /**
     * Aplica el degradado estatico correspondiente a [song]. Si [song]
     * es null, o no se puede extraer un color vivo de su caratula, cae
     * en el degradado neutro por defecto (mismos tonos base que
     * AmbientBackgroundController, para que la cola combine con el
     * resto de la app cuando no hay reproduccion).
     *
     * Se llama una vez al abrir la cola y de nuevo si la cancion
     * cambia mientras la cola sigue abierta (ver
     * QueueSheetController.onSongChanged()).
     */
    fun applyForSong(song: Song?) {

        if (song == null) {
            boundSongId = null
            applyColors(defaultTop(), defaultMiddle(), defaultBottom())
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

                val swatch =
                    palette?.vibrantSwatch
                        ?: palette?.lightVibrantSwatch
                        ?: palette?.darkVibrantSwatch
                        ?: palette?.mutedSwatch
                        ?: palette?.dominantSwatch

                if (swatch == null) {
                    applyColors(defaultTop(), defaultMiddle(), defaultBottom())
                    return@generate
                }

                val originalColor = swatch.rgb
                val topColor = darken(originalColor, 0.42f)
                val middleColor = darken(originalColor, 0.20f)
                val bottomColor = ColorUtils.blendARGB(originalColor, Color.BLACK, 0.90f)

                applyColors(topColor, middleColor, bottomColor)
            }
    }

    private fun applyColors(top: Int, middle: Int, bottom: Int) {
        gradientDrawable.colors = intArrayOf(top, middle, bottom)
        onBackgroundApplied?.invoke()
    }


    // ============================================================
    // OSCURECER COLOR
    // ============================================================

    private fun darken(color: Int, factor: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * 0.95f).coerceIn(0f, 1f)
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