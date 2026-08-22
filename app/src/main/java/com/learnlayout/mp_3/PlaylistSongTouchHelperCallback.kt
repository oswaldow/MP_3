package com.learnlayout.mp_3

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class PlaylistSongTouchHelperCallback(
    private val adapter: SongAdapter
) : ItemTouchHelper.Callback() {

    private var dragFromPosition: Int = -1

    override fun isLongPressDragEnabled(): Boolean = false

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
            return false
        }
        if (dragFromPosition == -1) {
            dragFromPosition = from
        }
        adapter.onItemMove(from, to)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // El swipe esta desactivado (ver isItemViewSwipeEnabled): quitar
        // una cancion de la playlist se hace desde el menu de 3 puntos.
    }

    override fun clearView(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ) {
        super.clearView(recyclerView, viewHolder)
        val finalPosition = viewHolder.bindingAdapterPosition
        if (dragFromPosition != -1 && finalPosition != RecyclerView.NO_POSITION) {
            adapter.onDragFinished(dragFromPosition, finalPosition)
        }
        dragFromPosition = -1
    }
}