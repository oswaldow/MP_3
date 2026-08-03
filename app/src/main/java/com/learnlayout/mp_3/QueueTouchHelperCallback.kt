package com.learnlayout.mp_3

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class QueueTouchHelperCallback(
    private val adapter: QueueAdapter,
    private val onSwipeToPlayNext: (Int) -> Unit
) : ItemTouchHelper.Callback() {

    private var dragFromPosition: Int = -1

    override fun isLongPressDragEnabled(): Boolean = false

    override fun isItemViewSwipeEnabled(): Boolean = true

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        val swipeFlags = ItemTouchHelper.RIGHT
        return makeMovementFlags(dragFlags, swipeFlags)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        if (dragFromPosition == -1) dragFromPosition = from
        adapter.onItemMove(from, to)
        return true
    }

    // Deslizar a la derecha manda la cancion a sonar a continuacion. Si ya
    // es la que esta sonando, no tiene sentido moverla: se regresa a su
    // lugar sin hacer nada.
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return

        if (position == adapter.getCurrentIndex()) {
            adapter.notifyItemChanged(position)
            return
        }

        onSwipeToPlayNext(position)
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
            drawPlayNextBackground(c, viewHolder, dX)
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        val finalPosition = viewHolder.bindingAdapterPosition
        if (dragFromPosition != -1 && finalPosition != RecyclerView.NO_POSITION) {
            adapter.onDragFinished(dragFromPosition, finalPosition)
        }
        dragFromPosition = -1
    }

    private fun drawPlayNextBackground(c: Canvas, viewHolder: RecyclerView.ViewHolder, dX: Float) {
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

        val icon = ContextCompat.getDrawable(context, R.drawable.ic_queue_music) ?: return
        val iconSize = (24 * density).toInt()
        val iconTop = itemView.top + (itemView.height - iconSize) / 2
        val iconLeft = itemView.left + (itemView.height - iconSize) / 2
        icon.setTint(ContextCompat.getColor(context, R.color.spotify_black))
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        icon.draw(c)
    }
}