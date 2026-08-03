package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress: Int = 0
    private var maxValue: Int = 100

    private val strokeWidthPx = dpToPx(3f)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = Color.parseColor("#33FFFFFF")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFD600")
    }

    private val arcRect = RectF()

    fun setProgress(current: Int, total: Int) {
        progress = current
        maxValue = if (total > 0) total else 1
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = strokeWidthPx / 2
        arcRect.set(padding, padding, w - padding, h - padding)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawOval(arcRect, backgroundPaint)
        val sweepAngle = (progress.toFloat() / maxValue.toFloat()) * 360f
        canvas.drawArc(arcRect, -90f, sweepAngle, false, progressPaint)
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}