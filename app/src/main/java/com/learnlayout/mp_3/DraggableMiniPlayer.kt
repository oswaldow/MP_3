package com.learnlayout.mp_3

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class DraggableMiniPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onDragStart: (() -> Unit)? = null
    var onDragMove: ((dy: Float) -> Unit)? = null
    var onDragEnd: ((dy: Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private var startY = 0f
    private var startX = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.rawY
                startX = ev.rawX
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - startY
                val dx = ev.rawX - startX
                if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    isDragging = true
                    onDragStart?.invoke()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                onDragMove?.invoke(startY - event.rawY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dy = startY - event.rawY
                if (isDragging) {
                    onDragEnd?.invoke(dy)
                } else {
                    onTap?.invoke()
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}