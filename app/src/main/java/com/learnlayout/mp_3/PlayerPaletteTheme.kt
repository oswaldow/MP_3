package com.learnlayout.mp_3

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/**
 * Extrae un color representativo de la caratula del album (estilo Material
 * You) y lo aplica animado como fondo de una vista del reproductor
 * expandido (ver viewPanelArtBanner en activity_song_list.xml, detras de
 * btnPanelBack / ivMonito / ivPanelAlbumArt) o, via [applyToDrawable], como
 * relleno del GradientDrawable del panel de letra (LyricsPanelController),
 * que ademas tiene su propio borde blanco fijo (no se toca aca).
 *
 * Orden de preferencia del swatch: oscuro y con algo de saturacion se lee
 * mejor detras del status bar y de iconos/texto blancos que un color muy
 * claro o muy gris plano, por eso el fallback va de
 * DarkVibrant -> DarkMuted -> Vibrant -> Muted -> dominante -> color por
 * defecto que pase quien llama (normalmente surface_dark).
 */
object PlayerPaletteTheme {

    private const val ANIM_DURATION_MS = 400L

    // Baja un poco el brillo (luminosidad HSL) del color extraido para que
    // el reloj/iconos del status bar y los botones blancos de arriba
    // siempre se lean bien encima, incluso con swatches muy brillantes.
    private const val DARKEN_FACTOR = 0.82f

    private val argbEvaluator = ArgbEvaluator()

    /**
     * Genera la paleta de [bitmap] en background (Palette ya usa su propio
     * hilo internamente) y anima el fondo de [targetView] al color elegido.
     * Si por lo que sea no se pudo generar paleta o no hay swatch usable,
     * cae en [fallbackColor].
     */
    fun applyFromBitmap(bitmap: Bitmap, targetView: View, fallbackColor: Int) {
        Palette.from(bitmap)
            .clearFilters() // no descartar tonos muy oscuros/claros: queremos el color real del album
            .generate { palette ->
                animateBackground(targetView, pickColor(palette, fallbackColor))
            }
    }

    /** Para cuando no hay caratula (placeholder): vuelve al color por defecto. */
    fun applyFallback(targetView: View, fallbackColor: Int) {
        animateBackground(targetView, fallbackColor)
    }

    /**
     * Igual que [applyFromBitmap] pero para un [GradientDrawable] en vez de
     * la vista completa. Se usa en el panel de letra (LyricsPanelController),
     * que necesita mantener su borde y sus esquinas redondeadas animadas
     * (GradientDrawable) en vez de solo un ColorDrawable de fondo.
     */
    fun applyToDrawable(bitmap: Bitmap, drawable: GradientDrawable, fallbackColor: Int) {
        Palette.from(bitmap)
            .clearFilters()
            .generate { palette ->
                animateDrawableColor(drawable, pickColor(palette, fallbackColor))
            }
    }

    /** Para cuando no hay caratula: vuelve el GradientDrawable al color por defecto. */
    fun applyDrawableFallback(drawable: GradientDrawable, fallbackColor: Int) {
        animateDrawableColor(drawable, fallbackColor)
    }

    private fun animateDrawableColor(drawable: GradientDrawable, targetColor: Int) {
        val currentColor = drawable.color?.defaultColor ?: targetColor
        if (currentColor == targetColor) {
            drawable.setColor(targetColor)
            return
        }
        ValueAnimator.ofObject(argbEvaluator, currentColor, targetColor).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim -> drawable.setColor(anim.animatedValue as Int) }
            start()
        }
    }

    private fun pickColor(palette: Palette?, fallbackColor: Int): Int {
        val swatch = palette?.darkVibrantSwatch
            ?: palette?.darkMutedSwatch
            ?: palette?.vibrantSwatch
            ?: palette?.mutedSwatch
            ?: palette?.dominantSwatch
            ?: return fallbackColor

        return darken(swatch.rgb)
    }

    private fun darken(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = (hsl[2] * DARKEN_FACTOR).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun animateBackground(view: View, targetColor: Int) {
        val currentColor = (view.background as? ColorDrawable)?.color ?: targetColor
        if (currentColor == targetColor) {
            view.setBackgroundColor(targetColor)
            return
        }
        ValueAnimator.ofObject(argbEvaluator, currentColor, targetColor).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim -> view.setBackgroundColor(anim.animatedValue as Int) }
            start()
        }
    }

    // ---------- Color de acento para los controles (play/pause, siguiente,
    // anterior, etc.) ----------

    /**
     * Extrae de [bitmap] un color de acento (vibrante, sin oscurecer a
     * proposito, a diferencia del banner) y anima la transicion desde
     * [currentColor] hasta ese color, llamando a [onColorAnimated] en cada
     * frame. Pensado para tintar varios botones a la vez (no una sola
     * vista), por eso no recibe una View sino un callback de color.
     */
    fun applyAccentFromBitmap(
        bitmap: Bitmap,
        fallbackColor: Int,
        currentColor: Int,
        onColorAnimated: (Int) -> Unit
    ) {
        Palette.from(bitmap)
            .clearFilters()
            .generate { palette ->
                animateColor(currentColor, pickAccentColor(palette, fallbackColor), onColorAnimated)
            }
    }

    /** Para cuando no hay caratula: vuelve al color de acento por defecto. */
    fun applyAccentFallback(fallbackColor: Int, currentColor: Int, onColorAnimated: (Int) -> Unit) {
        animateColor(currentColor, fallbackColor, onColorAnimated)
    }

    /**
     * A diferencia de [pickColor] (que oscurece para el fondo del banner),
     * este toma el swatch mas vibrante SIN oscurecer, porque va a tintar
     * iconos y el circulo del boton de play, que necesitan verse saturados.
     */
    private fun pickAccentColor(palette: Palette?, fallbackColor: Int): Int {
        val swatch = palette?.vibrantSwatch
            ?: palette?.lightVibrantSwatch
            ?: palette?.mutedSwatch
            ?: palette?.dominantSwatch
            ?: return fallbackColor
        return swatch.rgb
    }

    /**
     * Blanco o negro, segun cual contraste mejor sobre [backgroundColor].
     * Se usa para el icono del boton grande de play/pause, cuyo fondo
     * ahora es el color de acento (antes era @color/white fijo).
     */
    fun onColorFor(backgroundColor: Int): Int {
        return if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) Color.BLACK else Color.WHITE
    }

    private fun animateColor(from: Int, to: Int, onColorAnimated: (Int) -> Unit) {
        if (from == to) {
            onColorAnimated(to)
            return
        }
        ValueAnimator.ofObject(argbEvaluator, from, to).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim -> onColorAnimated(anim.animatedValue as Int) }
            start()
        }
    }
}