package com.learnlayout.mp_3

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class QueueAdapter(
    private val songs: MutableList<Song>,
    private var currentIndex: Int,
    private val onItemClick: (Int) -> Unit,
    private val onMoveFinished: (Int, Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    // Mantener presionada una cancion de la cola abre su menu de
    // opciones (Agregar a playlist / Editar nombre y artista /
    // Eliminar del dispositivo), igual que en la lista principal.
    // Con valor por defecto para no romper otros callers que todavia
    // no lo necesitan.
    private val onLongPress: (Int) -> Unit = {}
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    var dragStartListener: ((RecyclerView.ViewHolder) -> Unit)? = null

    inner class QueueViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        val ivIcon: ImageView =
            itemView.findViewById(R.id.ivQueueIcon)

        val ivPlaying: ImageView =
            itemView.findViewById(R.id.ivQueuePlaying)

        val tvTitle: TextView =
            itemView.findViewById(R.id.tvQueueTitle)

        val tvArtist: TextView =
            itemView.findViewById(R.id.tvQueueArtist)

        val tvPosition: TextView =
            itemView.findViewById(R.id.tvQueuePosition)

        val ivDragHandle: ImageView =
            itemView.findViewById(R.id.ivDragHandle)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): QueueViewHolder {

        val view =
            LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.item_queue_song,
                    parent,
                    false
                )

        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: QueueViewHolder,
        position: Int
    ) {

        val song = songs[position]

        val isCurrent =
            position == currentIndex

        holder.tvTitle.text =
            song.title

        holder.tvArtist.text =
            song.artist

        /*
         * La canción actual ya está identificada
         * visualmente en el bloque "SONANDO AHORA".
         *
         * En la lista mostramos solamente el indicador
         * de reproducción para no ocupar toda la columna.
         */
        holder.tvPosition.text =
            if (isCurrent) {
                "▶"
            } else {
                position
                    .toString()
                    .padStart(2, '0')
            }

        holder.itemView.setBackgroundResource(
            if (isCurrent) {
                R.drawable.bg_queue_item_current
            } else {
                R.drawable.bg_queue_item
            }
        )

        holder.ivPlaying.visibility =
            if (isCurrent) {
                View.VISIBLE
            } else {
                View.GONE
            }

        holder.tvPosition.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (isCurrent) {
                    R.color.purple_primary
                } else {
                    R.color.spotify_gray
                }
            )
        )

        bindAlbumArt(
            holder = holder,
            song = song,
            isCurrent = isCurrent
        )

        holder.itemView.setOnClickListener {

            val adapterPosition =
                holder.bindingAdapterPosition

            if (
                adapterPosition !=
                RecyclerView.NO_POSITION
            ) {
                onItemClick(adapterPosition)
            }
        }

        holder.itemView.setOnLongClickListener {

            val adapterPosition =
                holder.bindingAdapterPosition

            if (
                adapterPosition !=
                RecyclerView.NO_POSITION
            ) {
                onLongPress(adapterPosition)
            }

            true
        }

        holder.ivDragHandle.setOnTouchListener { _, event ->

            if (
                event.actionMasked ==
                MotionEvent.ACTION_DOWN
            ) {
                dragStartListener?.invoke(holder)
            }

            false
        }
    }

    private fun bindAlbumArt(
        holder: QueueViewHolder,
        song: Song,
        isCurrent: Boolean
    ) {

        holder.ivIcon.tag =
            song.id

        holder.ivIcon.animate().cancel()

        val cached =
            AlbumArtRepository.getCachedCover(song)

        if (cached != null) {

            applyBitmap(
                holder.ivIcon,
                cached
            )

            return
        }

        holder.ivIcon.setImageResource(
            R.drawable.ic_music_note
        )

        val padding =
            dp(
                holder.itemView,
                14
            )

        holder.ivIcon.setPadding(
            padding,
            padding,
            padding,
            padding
        )

        holder.ivIcon.imageTintList =
            ContextCompat.getColorStateList(
                holder.itemView.context,
                if (isCurrent) {
                    R.color.purple_primary
                } else {
                    R.color.spotify_gray
                }
            )

        holder.ivIcon.scaleType =
            ImageView.ScaleType.CENTER

        AlbumArtRepository.loadCover(
            context =
                holder.itemView.context,
            song = song,
            callback =
                object :
                    AlbumArtRepository.Callback {

                    override fun onCoverReady(
                        bitmap: Bitmap
                    ) {

                        if (
                            holder.ivIcon.tag !=
                            song.id
                        ) {
                            return
                        }

                        applyBitmap(
                            holder.ivIcon,
                            bitmap
                        )
                    }
                },
            isStillNeeded = {
                holder.ivIcon.tag == song.id
            }
        )
    }

    private fun applyBitmap(
        imageView: ImageView,
        bitmap: Bitmap
    ) {

        imageView.setPadding(
            0,
            0,
            0,
            0
        )

        imageView.imageTintList =
            null

        imageView.scaleType =
            ImageView.ScaleType.CENTER_CROP

        imageView.setImageBitmap(
            bitmap
        )
    }

    private fun dp(
        view: View,
        value: Int
    ): Int {

        return (
                value *
                        view.resources
                            .displayMetrics
                            .density
                ).toInt()
    }

    override fun getItemCount(): Int =
        songs.size

    fun getCurrentIndex(): Int =
        currentIndex

    /**
     * Actualiza únicamente el elemento marcado como canción actual.
     * No reemplaza el adapter ni modifica la posición del RecyclerView.
     */
    fun setCurrentIndex(newIndex: Int) {
        if (newIndex !in songs.indices || newIndex == currentIndex) return

        val previousIndex = currentIndex
        currentIndex = newIndex

        if (previousIndex in songs.indices) {
            notifyItemChanged(previousIndex)
        }
        notifyItemChanged(newIndex)
    }

    fun onItemMove(
        fromPosition: Int,
        toPosition: Int
    ) {

        if (
            fromPosition !in songs.indices ||
            toPosition !in songs.indices
        ) {
            return
        }

        val item =
            songs.removeAt(fromPosition)

        songs.add(
            toPosition,
            item
        )

        currentIndex =
            when {

                fromPosition == currentIndex ->
                    toPosition

                fromPosition < currentIndex &&
                        toPosition >= currentIndex ->
                    currentIndex - 1

                fromPosition > currentIndex &&
                        toPosition <= currentIndex ->
                    currentIndex + 1

                else ->
                    currentIndex
            }

        notifyItemMoved(
            fromPosition,
            toPosition
        )

        notifyItemChanged(
            currentIndex
        )
    }

    fun onDragFinished(
        fromPosition: Int,
        toPosition: Int
    ) {

        if (
            fromPosition != toPosition
        ) {
            onMoveFinished(
                fromPosition,
                toPosition
            )
        }
    }

    fun removeAt(
        position: Int
    ) {

        if (
            position !in songs.indices ||
            position == currentIndex
        ) {
            return
        }

        songs.removeAt(position)

        if (
            position < currentIndex
        ) {
            currentIndex--
        }

        notifyItemRemoved(position)

        notifyItemRangeChanged(
            position,
            (
                    itemCount -
                            position
                    ).coerceAtLeast(0)
        )

        onRemove(position)
    }
}