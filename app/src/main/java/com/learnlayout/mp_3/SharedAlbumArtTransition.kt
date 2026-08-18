package com.learnlayout.mp_3

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Transicion de "elemento compartido" para la caratula del album entre el
 * mini reproductor y el reproductor expandido.
 *
 * No usa la API de shared elements de Activity/Fragment (todo vive dentro
 * de la misma Activity, en un BottomSheet), asi que se simula a mano: se
 * crea una unica ImageView "volante" flotando sobre todo lo demas
 * ([overlay]), se coloca exactamente encima de la vista de origen, y en
 * cada frame de la animacion (o de onSlide) se reposiciona/redimensiona/
 * redondea interpolando hacia los bounds de la vista de destino.
 *
 * Mientras la vista volante esta activa, las dos ImageView reales (mini y
 * panel) se ocultan con alpha = 0f para que solo se vea la que esta
 * "volando"; al terminar, [end] las vuelve a mostrar y quita la volante.
 */
class SharedAlbumArtTransition(
    private val overlay: FrameLayout
) {

    private var flyingView: ImageView? = null

    val isActive: Boolean
        get() = flyingView != null

    /**
     * Arranca la transicion: crea la vista volante en los bounds actuales
     * de [source], con la misma imagen y esquinas redondeadas. Devuelve
     * false (y no hace nada) si [source] todavia no tiene tamano o imagen
     * validos, por ejemplo la primerisima vez que se abre el reproductor.
     */
    fun begin(source: ImageView, sourceCornerRadiusPx: Float): Boolean {
        val drawable = source.drawable ?: return false
        if (source.width <= 0 || source.height <= 0) return false

        val iv = ImageView(overlay.context).apply {
            scaleType = source.scaleType
            setImageDrawable(drawable)
            clipToOutline = true
            outlineProvider = RoundedOutlineProvider(sourceCornerRadiusPx)
        }

        overlay.removeAllViews()
        overlay.addView(iv, FrameLayout.LayoutParams(source.width, source.height))
        overlay.visibility = View.VISIBLE

        flyingView = iv
        positionFlyingView(iv, source, source.width.toFloat(), source.height.toFloat())
        return true
    }

    /**
     * Reposiciona/redimensiona/redondea la vista volante interpolando
     * entre los bounds de [source] (progress = 0) y [dest]
     * (progress = 1). Debe llamarse en cada frame mientras la transicion
     * este activa (ver [isActive]).
     */
    fun update(
        source: ImageView,
        dest: ImageView,
        sourceCornerRadiusPx: Float,
        destCornerRadiusPx: Float,
        progress: Float
    ) {
        val iv = flyingView ?: return
        if (dest.width <= 0 || dest.height <= 0) return

        val p = progress.coerceIn(0f, 1f)

        val sourceBounds = viewBoundsIn(overlay, source)
        val destBounds = viewBoundsIn(overlay, dest)

        val left = lerp(sourceBounds.left, destBounds.left, p)
        val top = lerp(sourceBounds.top, destBounds.top, p)
        val width = lerp(source.width.toFloat(), dest.width.toFloat(), p)
        val height = lerp(source.height.toFloat(), dest.height.toFloat(), p)
        val radius = lerp(sourceCornerRadiusPx, destCornerRadiusPx, p)

        val lp = iv.layoutParams as FrameLayout.LayoutParams
        lp.width = width.toInt().coerceAtLeast(1)
        lp.height = height.toInt().coerceAtLeast(1)
        iv.layoutParams = lp

        iv.translationX = left
        iv.translationY = top

        (iv.outlineProvider as? RoundedOutlineProvider)?.let {
            it.radiusPx = radius
            iv.invalidateOutline()
        }
    }

    /** Termina la transicion: quita la vista volante y oculta el overlay. */
    fun end() {
        if (flyingView == null) return
        overlay.removeAllViews()
        overlay.visibility = View.INVISIBLE
        flyingView = null
    }

    private fun positionFlyingView(iv: ImageView, source: ImageView, width: Float, height: Float) {
        val bounds = viewBoundsIn(overlay, source)
        val lp = iv.layoutParams as FrameLayout.LayoutParams
        lp.width = width.toInt().coerceAtLeast(1)
        lp.height = height.toInt().coerceAtLeast(1)
        iv.layoutParams = lp
        iv.translationX = bounds.left
        iv.translationY = bounds.top
    }

    /** Posicion de [view] relativa al [relativeTo], en px, usando coordenadas de pantalla. */
    private fun viewBoundsIn(relativeTo: View, view: View): Bounds {
        val relativeLocation = IntArray(2)
        relativeTo.getLocationOnScreen(relativeLocation)
        val viewLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        return Bounds(
            left = (viewLocation[0] - relativeLocation[0]).toFloat(),
            top = (viewLocation[1] - relativeLocation[1]).toFloat()
        )
    }

    private fun lerp(from: Float, to: Float, progress: Float): Float =
        from + (to - from) * progress

    private data class Bounds(val left: Float, val top: Float)

    private class RoundedOutlineProvider(var radiusPx: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }
}