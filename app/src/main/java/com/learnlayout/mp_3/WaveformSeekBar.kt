package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/**
 * Barra de progreso estilo forma de onda (waveform), pensada como
 * reemplazo visual de un SeekBar clasico.
 *
 * No analiza el audio real: genera un patron de barras pseudo-aleatorio
 * pero estable (a partir de un seed) para que cada cancion tenga
 * "su" onda y no cambie en cada redibujo.
 */
class WaveformSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnWaveformSeekListener {
        fun onProgressChanged(progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch()
        fun onStopTrackingTouch(progress: Int)
    }

    var listener: OnWaveformSeekListener? = null

    var max: Int = 100
        set(value) {
            field = if (value > 0) value else 1
            invalidate()
        }

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, max)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val barWidthPx = 2.5f * density
    private val spacingPx = 2.5f * density
    private val minBarHeightPx = 3f * density

    private var seed: Long = 0L
    private var barHeights: FloatArray = FloatArray(0)
    private var isDragging = false

    // Colores estilo Spotify: verde para lo reproducido, gris tenue para el resto
    private val playedColor = Color.parseColor("#FFD600")
    private val unplayedColor = Color.parseColor("#4DFFFFFF")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = barWidthPx
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    fun setWaveformSeed(newSeed: Long) {
        if (seed != newSeed) {
            seed = newSeed
            regenerateWaveform(width)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        regenerateWaveform(w)
    }

    private fun regenerateWaveform(widthPx: Int) {
        val usableWidth = widthPx - paddingLeft - paddingRight
        val step = barWidthPx + spacingPx
        val count = if (step > 0f) max(1, (usableWidth / step).toInt()) else 0
        barHeights = generateWaveform(count, seed)
        invalidate()
    }

    private fun generateWaveform(count: Int, seed: Long): FloatArray {
        if (count <= 0) return FloatArray(0)
        val rnd = Random(seed)
        val values = FloatArray(count)
        var prev = 0.45f
        for (i in 0 until count) {
            val target = 0.18f + rnd.nextFloat() * 0.82f
            prev = prev * 0.6f + target * 0.4f
            values[i] = prev
        }
        for (i in values.indices) {
            val noise = (rnd.nextFloat() - 0.5f) * 0.22f
            values[i] = (values[i] + noise).coerceIn(0.12f, 1f)
        }
        return values
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (barHeights.isEmpty()) return

        val centerY = height / 2f
        val maxBarHeightPx = height - paddingTop - paddingBottom
        val fraction = progress.toFloat() / max.toFloat()
        val playedBars = (fraction * barHeights.size).toInt()

        var x = paddingLeft + barWidthPx / 2f
        for (i in barHeights.indices) {
            val barHeight = (barHeights[i] * maxBarHeightPx).coerceAtLeast(minBarHeightPx)
            paint.color = if (i <= playedBars) playedColor else unplayedColor
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
            x += barWidthPx + spacingPx
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                updateFromTouch(event.x)
                listener?.onStartTrackingTouch()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) updateFromTouch(event.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    updateFromTouch(event.x)
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    listener?.onStopTrackingTouch(progress)
                }
            }
        }
        return true
    }

    private fun updateFromTouch(x: Float) {
        val usableWidth = (width - paddingLeft - paddingRight).toFloat()
        if (usableWidth <= 0f) return
        val fraction = ((x - paddingLeft) / usableWidth).coerceIn(0f, 1f)
        progress = (fraction * max).toInt()
        listener?.onProgressChanged(progress, true)
    }
}