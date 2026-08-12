package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Vista de respuesta del ecualizador.
 * No reemplaza los SeekBar: muestra visualmente la curva actual y sus puntos.
 */
class EqCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var frequencies = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    private var gainsDb = FloatArray(frequencies.size)
    private var accentColor = ContextCompat.getColor(context, R.color.spotify_green)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9f)
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
    }

    fun setFrequencies(values: IntArray) {
        frequencies = values.copyOf()
        if (gainsDb.size != frequencies.size) gainsDb = FloatArray(frequencies.size)
        invalidate()
    }

    fun setGainsDb(values: FloatArray) {
        gainsDb = values.copyOf()
        if (gainsDb.size != frequencies.size) {
            gainsDb = FloatArray(frequencies.size) { index -> values.getOrElse(index) { 0f } }
        }
        invalidate()
    }

    fun setAccentColor(color: Int) {
        accentColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft + dp(12f)
        val right = width - paddingRight - dp(12f)
        val top = paddingTop + dp(16f)
        val bottom = height - paddingBottom - dp(26f)
        val chartWidth = right - left
        val chartHeight = bottom - top
        if (chartWidth <= 0f || chartHeight <= 0f) return

        // 0 dB central, rango visual ±12 dB.
        val minDb = -12f
        val maxDb = 12f

        gridPaint.color = 0x20FFFFFF
        gridPaint.strokeWidth = dp(1f)

        val horizontalLevels = floatArrayOf(-12f, -6f, 0f, 6f, 12f)
        horizontalLevels.forEach { db ->
            val y = dbToY(db, top, bottom, minDb, maxDb)
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        // Líneas verticales sutiles por banda.
        frequencies.forEachIndexed { index, _ ->
            val x = indexToX(index, frequencies.size, left, right)
            canvas.drawLine(x, top, x, bottom, gridPaint)
        }

        labelPaint.color = 0x88FFFFFF.toInt()
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("+12", left, top - dp(4f), labelPaint)
        canvas.drawText("0", left, dbToY(0f, top, bottom, minDb, maxDb) - dp(4f), labelPaint)
        canvas.drawText("-12", left, bottom - dp(2f), labelPaint)

        if (gainsDb.isEmpty()) return

        val points = ArrayList<Pair<Float, Float>>(gainsDb.size)
        gainsDb.forEachIndexed { index, gain ->
            val x = indexToX(index, gainsDb.size, left, right)
            val y = dbToY(gain.coerceIn(minDb, maxDb), top, bottom, minDb, maxDb)
            points += x to y
        }

        val path = Path()
        path.moveTo(points.first().first, points.first().second)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val current = points[i]
            val midX = (prev.first + current.first) / 2f
            path.cubicTo(midX, prev.second, midX, current.second, current.first, current.second)
        }

        // Relleno muy sutil bajo la curva.
        val fillPath = Path(path)
        fillPath.lineTo(points.last().first, bottom)
        fillPath.lineTo(points.first().first, bottom)
        fillPath.close()
        fillPaint.color = withAlpha(accentColor, 0x22)
        canvas.drawPath(fillPath, fillPaint)

        curvePaint.color = accentColor
        canvas.drawPath(path, curvePaint)

        pointPaint.color = accentColor
        points.forEach { (x, y) ->
            canvas.drawCircle(x, y, dp(5f), pointPaint)
            pointPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(x, y, dp(2f), pointPaint)
            pointPaint.color = accentColor
        }
    }

    private fun dbToY(db: Float, top: Float, bottom: Float, minDb: Float, maxDb: Float): Float {
        val normalized = (db - minDb) / (maxDb - minDb)
        return bottom - normalized * (bottom - top)
    }

    private fun indexToX(index: Int, count: Int, left: Float, right: Float): Float {
        if (count <= 1) return (left + right) / 2f
        return left + (right - left) * index / (count - 1).toFloat()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}