package com.learnlayout.mp_3

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SongSwipeToQueueCallback(
    private val onSwipeToPlayNext: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    // La cancion se agrega a la cola pero se queda en la lista, asi que se
    // regresa a su posicion original con notifyItemChanged en vez de
    // dejar que ItemTouchHelper la elimine visualmente.
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        onSwipeToPlayNext(position)
        viewHolder.bindingAdapter?.notifyItemChanged(position)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0) {
            val itemView = viewHolder.itemView
            val context = itemView.context
            val density = context.resources.displayMetrics.density

            val background = Paint().apply {
                color = ContextCompat.getColor(context, R.color.purple_primary)
                isAntiAlias = true
            }
            val cornerRadius = 16f * density
            val rect = RectF(
                itemView.left.toFloat(),
                itemView.top.toFloat(),
                dX,
                itemView.bottom.toFloat()
            )
            c.drawRoundRect(rect, cornerRadius, cornerRadius, background)

            val icon = ContextCompat.getDrawable(context, R.drawable.ic_queue_music)
            if (icon != null) {
                val iconSize = (24 * density).toInt()
                val iconTop = itemView.top + (itemView.height - iconSize) / 2
                val iconLeft = itemView.left + (itemView.height - iconSize) / 2
                icon.setTint(ContextCompat.getColor(context, R.color.spotify_black))
                icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                icon.draw(c)
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}