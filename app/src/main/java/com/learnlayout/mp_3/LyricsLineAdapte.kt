package com.learnlayout.mp_3

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView

class LyricsLineAdapter(
    private val lines: List<LyricsLine>,
    // Si no es null, las lineas se vuelven tocables (modo sincronizacion
    // manual) y se invoca con la posicion tocada.
    private val onLineTap: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<LyricsLineAdapter.LineViewHolder>() {

    private var activeIndex: Int = -1

    // Indices ya marcados con un timestamp manual (ver LyricsActivity).
    // Solo se usa cuando onLineTap != null.
    private val taggedIndices = mutableSetOf<Int>()

    class LineViewHolder(val tvLine: TextView) : RecyclerView.ViewHolder(tvLine) {
        var activeAnimator: ValueAnimator? = null
        var glowRadius: Float = 0f
    }

    // Item fantasma al final para que el layout manager nunca "ancle" la
    // ultima linea real al fondo del RecyclerView (ver scrollLyricsToLine).
    override fun getItemViewType(position: Int): Int =
        if (position < lines.size) TYPE_LINE else TYPE_FOOTER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyrics_line, parent, false) as TextView
        if (viewType == TYPE_FOOTER) {
            view.text = ""
            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                view.resources.displayMetrics.heightPixels
            )
        }
        return LineViewHolder(view)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        if (getItemViewType(position) == TYPE_FOOTER) return

        val line = lines[position]
        val isTappable = onLineTap != null
        val isTagged = position in taggedIndices
        holder.tvLine.text = when {
            line.text.isBlank() -> "♪"
            isTappable && isTagged -> "✓  ${line.text}"
            else -> line.text
        }

        val isActive = position == activeIndex

        holder.activeAnimator?.cancel()
        holder.tvLine.translationY = 0f
        holder.tvLine.scaleX = 1f
        holder.tvLine.scaleY = 1f
        holder.tvLine.alpha = if (isActive) 1.0f else if (isTappable && isTagged) 0.65f else INACTIVE_ALPHA
        holder.tvLine.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (isActive) ACTIVE_TEXT_SIZE_SP else INACTIVE_TEXT_SIZE_SP
        )
        holder.tvLine.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)

        val glow = if (isActive) {
            ACTIVE_GLOW_RADIUS_DP * holder.tvLine.resources.displayMetrics.density
        } else {
            0f
        }
        holder.glowRadius = glow
        applyGlow(holder.tvLine, glow)

        if (isTappable) {
            holder.tvLine.isClickable = true
            holder.tvLine.setOnClickListener { onLineTap.invoke(position) }
        } else {
            holder.tvLine.isClickable = false
            holder.tvLine.setOnClickListener(null)
        }
    }

    // Anima solo el TextView (alpha, tamano, un desplazamiento en Y y un
    // brillo). No toca scroll ni layout: la linea activa "sube" desde un
    // offset propio y se ilumina; la que deja de ser activa solo se atenua.
    private fun animateLineState(holder: LineViewHolder, isActive: Boolean) {
        val view = holder.tvLine
        holder.activeAnimator?.cancel()

        val density = view.resources.displayMetrics.density
        val riseOffsetPx = RISE_OFFSET_DP * density
        val activeGlowPx = ACTIVE_GLOW_RADIUS_DP * density

        val startAlpha = view.alpha
        val endAlpha = if (isActive) 1.0f else INACTIVE_ALPHA
        val endSizeSp = if (isActive) ACTIVE_TEXT_SIZE_SP else INACTIVE_TEXT_SIZE_SP
        val startGlow = holder.glowRadius
        val endGlow = if (isActive) activeGlowPx else 0f

        val startTranslationY = if (isActive) riseOffsetPx else view.translationY
        view.translationY = startTranslationY

        view.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)

        val currentApparentSizeSp =
            (view.textSize / view.resources.displayMetrics.scaledDensity) * view.scaleX
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, endSizeSp)
        view.pivotX = 0f
        view.pivotY = 0f
        val startScale = currentApparentSizeSp / endSizeSp
        view.scaleX = startScale
        view.scaleY = startScale

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LINE_TRANSITION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float

                val moveFraction = (fraction * 1.35f).coerceAtMost(1f)
                view.translationY = startTranslationY + (0f - startTranslationY) * moveFraction

                view.alpha = startAlpha + (endAlpha - startAlpha) * fraction

                val scale = startScale + (1f - startScale) * fraction
                view.scaleX = scale
                view.scaleY = scale

                val glowRadius = startGlow + (endGlow - startGlow) * fraction
                holder.glowRadius = glowRadius
                applyGlow(view, glowRadius)
            }
        }
        holder.activeAnimator = animator
        animator.start()
    }

    private fun applyGlow(view: TextView, radius: Float) {
        if (radius > 0.5f) {
            view.setShadowLayer(radius, 0f, 0f, GLOW_COLOR)
        } else {
            view.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    override fun getItemCount(): Int = lines.size + 1

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
            activeIndex = newIndex
            return newIndex
        }
        return -1
    }

    /**
     * Resalta directamente la linea "index" como activa, sin depender del
     * tiempo de reproduccion. La usa el modo de sincronizacion manual para
     * marcar cual es la siguiente linea pendiente de tocar.
     */
    fun setActiveIndexDirect(index: Int) {
        if (index == activeIndex) return
        val previous = activeIndex
        activeIndex = index
        if (previous in lines.indices) notifyItemChanged(previous)
        if (index in lines.indices) notifyItemChanged(index)
    }

    /** Marca una linea como ya sincronizada manualmente (le pone el check). */
    fun markTagged(index: Int) {
        taggedIndices.add(index)
        notifyItemChanged(index)
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
        private const val TYPE_LINE = 0
        private const val TYPE_FOOTER = 1
        private const val LINE_TRANSITION_MS = 420L
        private const val INACTIVE_ALPHA = 0.45f
        private const val ACTIVE_TEXT_SIZE_SP = 20f
        private const val INACTIVE_TEXT_SIZE_SP = 17f
        private const val RISE_OFFSET_DP = 36f
        private const val ACTIVE_GLOW_RADIUS_DP = 8f
        private val GLOW_COLOR = Color.argb(190, 255, 255, 255)
    }
}