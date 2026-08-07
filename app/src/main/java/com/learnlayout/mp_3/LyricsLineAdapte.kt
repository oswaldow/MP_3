package com.learnlayout.mp_3

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LyricsLineAdapter(
    private val lines: List<LyricsLine>
) : RecyclerView.Adapter<LyricsLineAdapter.LineViewHolder>() {

    private var activeIndex: Int = -1

    class LineViewHolder(val tvLine: TextView) : RecyclerView.ViewHolder(tvLine) {
        // Animator en curso para esta linea, si lo hay. Se cancela antes de
        // arrancar uno nuevo por si el usuario cambia de cancion muy rapido
        // y el ViewHolder se reutiliza a mitad de una animacion.
        var activeAnimator: ValueAnimator? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyrics_line, parent, false) as TextView
        return LineViewHolder(view)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        val line = lines[position]
        holder.tvLine.text = line.text.ifBlank { "♪" }

        val isActive = position == activeIndex

        // Se detiene cualquier animacion previa sobre este ViewHolder
        // reciclado y se deja el TextView directamente en el estado final
        // que le corresponde (sin animacion), asi el bind inicial (o el
        // reciclaje al hacer scroll) nunca arranca de un estado a medias.
        holder.activeAnimator?.cancel()
        holder.tvLine.alpha = if (isActive) 1.0f else INACTIVE_ALPHA
        holder.tvLine.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (isActive) ACTIVE_TEXT_SIZE_SP else INACTIVE_TEXT_SIZE_SP
        )
        holder.tvLine.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * Anima el cambio visual de una linea especifica entre su estado activo
     * e inactivo (alpha + tamano de texto). No mueve el scroll ni el
     * layout de la lista: solo el propio texto crece/se ilumina o se
     * encoge/atenua suavemente.
     *
     * Se anima el textSize real (no una escala visual/scaleX-scaleY) a
     * proposito: el TextView ocupa todo el ancho del panel
     * (match_parent), asi que escalarlo visualmente hace que el texto ya
     * envuelto (wrap) para ese ancho se salga del contenedor por los
     * costados (bug visto en pantalla: primeras/ultimas letras cortadas).
     * Animando el tamano real, Android vuelve a envolver el texto en cada
     * frame dentro del mismo ancho disponible, así que nunca se desborda.
     */
    private fun animateLineState(holder: LineViewHolder, isActive: Boolean) {
        val view = holder.tvLine
        holder.activeAnimator?.cancel()

        val startAlpha = view.alpha
        val endAlpha = if (isActive) 1.0f else INACTIVE_ALPHA
        val startSizeSp = view.textSize / view.resources.displayMetrics.scaledDensity
        val endSizeSp = if (isActive) ACTIVE_TEXT_SIZE_SP else INACTIVE_TEXT_SIZE_SP

        view.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LINE_TRANSITION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                view.alpha = startAlpha + (endAlpha - startAlpha) * fraction
                val sizeSp = startSizeSp + (endSizeSp - startSizeSp) * fraction
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            }
        }
        holder.activeAnimator = animator
        animator.start()
    }

    override fun getItemCount(): Int = lines.size

    /**
     * Actualiza la línea activa según la posición actual de reproducción (ms).
     * Devuelve el índice activo si cambió, o -1 si no hubo cambio.
     */
    fun updateActiveLine(positionMs: Long): Int {
        var newIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) {
                newIndex = i
            } else {
                break
            }
        }
        if (newIndex != activeIndex) {
            val previous = activeIndex
            activeIndex = newIndex
            return newIndex
        }
        return -1
    }

    fun animateActiveLineChange(recyclerView: RecyclerView, previousIndex: Int, newIndex: Int) {
        if (previousIndex in lines.indices) {
            (recyclerView.findViewHolderForAdapterPosition(previousIndex) as? LineViewHolder)?.let {
                animateLineState(it, isActive = false)
            }
        }
        if (newIndex in lines.indices) {
            (recyclerView.findViewHolderForAdapterPosition(newIndex) as? LineViewHolder)?.let {
                animateLineState(it, isActive = true)
            }
        }
    }

    fun getActiveIndex(): Int = activeIndex

    fun getLineAt(index: Int): LyricsLine? = lines.getOrNull(index)

    companion object {
        private const val LINE_TRANSITION_MS = 220L
        private const val INACTIVE_ALPHA = 0.45f
        private const val ACTIVE_TEXT_SIZE_SP = 20f
        private const val INACTIVE_TEXT_SIZE_SP = 17f
    }
}