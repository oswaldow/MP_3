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

object PlayerPaletteTheme {

    private const val ANIM_DURATION_MS = 400L

    private const val DARKEN_FACTOR = 0.82f

    // Tope de luminosidad (HSL lightness) para el fondo del banner y del
    // panel de letras. tvPanelSongTitle/tvPanelArtist e item_lyrics_line
    // usan texto blanco/gris claro FIJO (text_primary_light, spotify_gray),
    // no recalculan su color segun el fondo. DARKEN_FACTOR por si solo no
    // basta: escala el lightness original de forma proporcional, asi que
    // una caratula muy clara (blanca, pastel, monocromatica) puede seguir
    // quedando por encima de 0.7-0.8 de lightness aun despues de oscurecer,
    // y el texto claro se pierde. Este tope garantiza que el fondo nunca
    // quede tan claro como para romper ese contraste, sin importar que tan
    // clara sea la caratula de origen.
    private const val MAX_BACKGROUND_LIGHTNESS = 0.30f

    private const val MIN_ACCENT_SATURATION = 0.20f

    // Contraste minimo (formula WCAG, via ColorUtils.calculateContrast)
    // que debe haber entre el color de acento y el fondo real que tiene
    // detras un icono "suelto" (sin su propio circulo de fondo, ej.
    // btnPanelPrevious/Next/Queue/Back/AddToPlaylist) para considerarse
    // legible. Por debajo de esto, iconColorFor() cae a blanco o negro
    // puro en vez del acento. 3.0 es el minimo que recomienda WCAG para
    // graficos/componentes de UI (no texto).
    private const val MIN_ICON_CONTRAST = 3.0f

    private val argbEvaluator = ArgbEvaluator()

    // fallbackColor va antes del vararg (Kotlin exige que el vararg quede
    // al final si se llama con argumentos posicionales). Se acepta 1+
    // vistas para poder themear banner + mini-player con el mismo color
    // ya calculado, sin recalcular la paleta por cada vista (ver
    // PlayerPanelController: viewPanelArtBanner + groupMini).
    //
    // onColorPicked se llama con el color final ya elegido (antes de que
    // termine la animacion) para que el llamador pueda guardarlo y usarlo
    // despues, por ejemplo para calcular contraste de otros elementos
    // contra este mismo fondo (ver iconColorFor / currentBannerColor en
    // PlayerPanelController). Default vacio para no romper llamadores que
    // no lo necesiten.
    fun applyFromBitmap(
        bitmap: Bitmap,
        fallbackColor: Int,
        onColorPicked: (Int) -> Unit = {},
        vararg targetViews: View
    ) {
        Palette.from(bitmap)
            .clearFilters() // no descartar tonos muy oscuros/claros: queremos el color real del album
            .generate { palette ->
                val color = pickColor(palette, fallbackColor)
                targetViews.forEach { animateBackground(it, color) }
                onColorPicked(color)
            }
    }

    fun applyFallback(fallbackColor: Int, onColorPicked: (Int) -> Unit = {}, vararg targetViews: View) {
        targetViews.forEach { animateBackground(it, fallbackColor) }
        onColorPicked(fallbackColor)
    }

    fun applyToDrawable(bitmap: Bitmap, drawable: GradientDrawable, fallbackColor: Int) {
        Palette.from(bitmap)
            .clearFilters()
            .generate { palette ->
                animateDrawableColor(drawable, pickColor(palette, fallbackColor))
            }
    }

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
        // Se escala primero por DARKEN_FACTOR (para que colores ya oscuros
        // no se vean planos) y despues se aplica el tope: el resultado nunca
        // puede superar MAX_BACKGROUND_LIGHTNESS, sin importar que tan claro
        // sea el color de entrada.
        hsl[2] = (hsl[2] * DARKEN_FACTOR).coerceIn(0f, MAX_BACKGROUND_LIGHTNESS)
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

    private fun pickAccentColor(palette: Palette?, fallbackColor: Int): Int {
        val swatch = palette?.vibrantSwatch
            ?: palette?.lightVibrantSwatch
            ?: palette?.mutedSwatch
            ?: palette?.dominantSwatch
            ?: return fallbackColor

        return if (saturationOf(swatch.rgb) >= MIN_ACCENT_SATURATION) {
            swatch.rgb
        } else {
            fallbackColor
        }
    }

    private fun saturationOf(color: Int): Float {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        return hsl[1]
    }

    fun onColorFor(backgroundColor: Int): Int {
        return if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) Color.BLACK else Color.WHITE
    }

    /**
     * Color a usar para un icono "suelto" (sin su propio circulo de
     * fondo) que se dibuja directo sobre [backgroundColor]. Si
     * [preferredColor] (normalmente el acento Material You) ya tiene
     * contraste suficiente contra ese fondo, se usa tal cual para
     * mantener el tono de la caratula. Si no (caratulas muy
     * monocromaticas, donde el acento sale casi del mismo tono que el
     * fondo y el icono se vuelve invisible), cae a blanco o negro puro
     * segun [onColorFor], que siempre garantiza contraste maximo.
     */
    fun iconColorFor(backgroundColor: Int, preferredColor: Int): Int {
        val contrast = ColorUtils.calculateContrast(preferredColor, backgroundColor)
        return if (contrast >= MIN_ICON_CONTRAST) preferredColor else onColorFor(backgroundColor)
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