package com.learnlayout.mp_3

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import java.util.WeakHashMap

/**
 * Animacion de "toque" para los botones de icono de la pantalla del
 * reproductor: al presionar, el fondo (ovalado) del boton se rellena
 * suavemente de blanco (semi-transparente) y luego se desvanece de
 * vuelta a transparente.
 *
 * El relleno es siempre blanco, independientemente del acento Material
 * You vigente, para que el toque se sienta suave y consistente en
 * cualquier caratula.
 */
object ButtonTapFillAnimator {

    private const val FILL_DURATION_MS = 160L
    private const val UNFILL_DURATION_MS = 380L

    // Blanco suave: ~35% de opacidad para que el relleno no se sienta
    // agresivo ni tape el icono.
    private const val SOFT_WHITE_FILL = 0x59FFFFFF.toInt()
    private const val TRANSPARENT = 0x00FFFFFF

    private val fillDrawables = WeakHashMap<View, GradientDrawable>()
    private val runningAnimators = WeakHashMap<View, Animator>()

    private fun fillDrawableFor(view: View): GradientDrawable =
        fillDrawables.getOrPut(view) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0) // transparente
            }.also { view.background = it }
        }

    /** Reemplaza el click listener del boton para que, ademas de ejecutar
     * [action], dispare el flash de relleno blanco suave.
     *
     * [getColor] se mantiene por compatibilidad con las llamadas
     * existentes, pero ya no se usa para pintar el relleno: el relleno
     * siempre es blanco. */
    fun setOnClickListener(view: View, getColor: () -> Int, action: () -> Unit) {
        view.setOnClickListener {
            playFill(view)
            action()
        }
    }

    private fun playFill(view: View) {
        val drawable = fillDrawableFor(view)

        runningAnimators[view]?.cancel()

        val fillOut = ValueAnimator.ofArgb(SOFT_WHITE_FILL, TRANSPARENT).apply {
            duration = UNFILL_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { drawable.setColor(it.animatedValue as Int) }
        }
        val fillIn = ValueAnimator.ofArgb(TRANSPARENT, SOFT_WHITE_FILL).apply {
            duration = FILL_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { drawable.setColor(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (runningAnimators[view] !== animation) return
                    runningAnimators[view] = fillOut
                    fillOut.start()
                }
            })
        }

        runningAnimators[view] = fillIn
        fillIn.start()
    }
}