package com.learnlayout.mp_3

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QueueAdapter(
    private val songs: MutableList<Song>,
    private var currentIndex: Int,
    private val onItemClick: (Int) -> Unit,
    private val onMoveFinished: (Int, Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    var dragStartListener: ((RecyclerView.ViewHolder) -> Unit)? = null

    inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivQueueIcon)
        val tvTitle: TextView = itemView.findViewById(R.id.tvQueueTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvQueueArtist)
        val ivDragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_song, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val song = songs[position]
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist

        val isCurrent = position == currentIndex
        holder.ivIcon.setImageResource(
            if (isCurrent) R.drawable.ic_equalizer else R.drawable.ic_music_note
        )

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick(pos)
        }

        holder.ivDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                dragStartListener?.invoke(holder)
            }
            false
        }
    }

    override fun getItemCount(): Int = songs.size

    fun getCurrentIndex(): Int = currentIndex

    // Reacomoda visualmente durante el arrastre. currentIndex se ajusta en
    // vivo para que el icono de "sonando" siga a la cancion correcta.
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        val item = songs.removeAt(fromPosition)
        songs.add(toPosition, item)

        currentIndex = when {
            fromPosition == currentIndex -> toPosition
            fromPosition < currentIndex && toPosition >= currentIndex -> currentIndex - 1
            fromPosition > currentIndex && toPosition <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }

        notifyItemMoved(fromPosition, toPosition)
    }

    // Se llama al soltar el elemento arrastrado, con la posicion original y
    // la final, para persistir el nuevo orden en MusicService.
    fun onDragFinished(fromPosition: Int, toPosition: Int) {
        if (fromPosition != toPosition) {
            onMoveFinished(fromPosition, toPosition)
        }
    }
}