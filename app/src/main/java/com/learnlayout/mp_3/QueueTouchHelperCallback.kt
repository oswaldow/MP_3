package com.learnlayout.mp_3

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class QueueTouchHelperCallback(
    private val adapter: QueueAdapter,
    private val onSwipeToPlayNext: (Int) -> Unit,
    private val onSwipeToRemove: (Int) -> Unit
) : ItemTouchHelper.Callback() {

    private var dragFromPosition: Int = -1

    override fun isLongPressDragEnabled(): Boolean =
        false

    override fun isItemViewSwipeEnabled(): Boolean =
        true

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {

        val dragFlags =
            ItemTouchHelper.UP or
                    ItemTouchHelper.DOWN

        val swipeFlags =
            ItemTouchHelper.RIGHT or
                    ItemTouchHelper.LEFT

        return makeMovementFlags(
            dragFlags,
            swipeFlags
        )
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {

        val from =
            viewHolder.bindingAdapterPosition

        val to =
            target.bindingAdapterPosition

        if (
            from == RecyclerView.NO_POSITION ||
            to == RecyclerView.NO_POSITION
        ) {
            return false
        }

        if (dragFromPosition == -1) {
            dragFromPosition = from
        }

        adapter.onItemMove(
            from,
            to
        )

        return true
    }

    override fun onSwiped(
        viewHolder: RecyclerView.ViewHolder,
        direction: Int
    ) {

        val position =
            viewHolder.bindingAdapterPosition

        if (
            position ==
            RecyclerView.NO_POSITION
        ) {
            return
        }

        /*
         * La canción que está sonando
         * nunca puede eliminarse ni moverse
         * mediante swipe.
         */
        if (
            position ==
            adapter.getCurrentIndex()
        ) {

            adapter.notifyItemChanged(
                position
            )

            return
        }

        when (direction) {

            ItemTouchHelper.RIGHT -> {
                onSwipeToPlayNext(
                    position
                )
            }

            ItemTouchHelper.LEFT -> {
                onSwipeToRemove(
                    position
                )
            }
        }
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

        if (
            actionState ==
            ItemTouchHelper.ACTION_STATE_SWIPE &&
            dX != 0f
        ) {

            if (dX > 0f) {

                /*
                 * Swipe derecha:
                 * Sonará a continuación.
                 */
                drawActionBackground(
                    canvas = c,
                    viewHolder = viewHolder,
                    dX = dX,
                    colorRes =
                        R.color.purple_primary,
                    iconRes =
                        R.drawable.ic_queue_music
                )

            } else {

                /*
                 * Swipe izquierda:
                 * Quitar de la cola.
                 */
                drawActionBackground(
                    canvas = c,
                    viewHolder = viewHolder,
                    dX = dX,
                    colorRes =
                        R.color.spotify_gray,
                    iconRes =
                        R.drawable.ic_delete
                )
            }
        }

        super.onChildDraw(
            c,
            recyclerView,
            viewHolder,
            dX,
            dY,
            actionState,
            isCurrentlyActive
        )
    }

    override fun clearView(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ) {

        super.clearView(
            recyclerView,
            viewHolder
        )

        val finalPosition =
            viewHolder.bindingAdapterPosition

        if (
            dragFromPosition != -1 &&
            finalPosition !=
            RecyclerView.NO_POSITION
        ) {

            adapter.onDragFinished(
                dragFromPosition,
                finalPosition
            )
        }

        dragFromPosition = -1
    }

    private fun drawActionBackground(
        canvas: Canvas,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        colorRes: Int,
        iconRes: Int
    ) {

        val itemView =
            viewHolder.itemView

        val context =
            itemView.context

        val density =
            context.resources
                .displayMetrics
                .density

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    ContextCompat.getColor(
                        context,
                        colorRes
                    )
            }

        val cornerRadius =
            16f * density

        val left =
            if (dX > 0f) {
                itemView.left.toFloat()
            } else {
                dX
            }

        val right =
            if (dX > 0f) {
                dX
            } else {
                itemView.right.toFloat()
            }

        canvas.drawRoundRect(
            RectF(
                left,
                itemView.top.toFloat(),
                right,
                itemView.bottom.toFloat()
            ),
            cornerRadius,
            cornerRadius,
            paint
        )

        val icon =
            ContextCompat.getDrawable(
                context,
                iconRes
            ) ?: return

        val iconSize =
            (24 * density).toInt()

        val iconTop =
            itemView.top +
                    (
                            itemView.height -
                                    iconSize
                            ) / 2

        val iconLeft =
            if (dX > 0f) {

                itemView.left +
                        (
                                itemView.height -
                                        iconSize
                                ) / 2

            } else {

                itemView.right -
                        (
                                itemView.height +
                                        iconSize
                                ) / 2
            }

        icon.setTint(
            ContextCompat.getColor(
                context,
                R.color.spotify_black
            )
        )

        icon.setBounds(
            iconLeft,
            iconTop,
            iconLeft + iconSize,
            iconTop + iconSize
        )

        icon.draw(canvas)
    }
}