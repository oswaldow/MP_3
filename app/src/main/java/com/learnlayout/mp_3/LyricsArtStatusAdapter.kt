package com.learnlayout.mp_3

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class LyricsArtStatusAdapter(
    private var items: List<SongDownloadStatus>,
    private val onItemClick: (SongDownloadStatus) -> Unit,
    private val onEditClick: (SongDownloadStatus) -> Unit,
    private val onForceRefreshClick: (SongDownloadStatus) -> Unit
) : RecyclerView.Adapter<LyricsArtStatusAdapter.StatusViewHolder>() {

    inner class StatusViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvStatusItemTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvStatusItemArtist)
        val ivLyricsStatus: ImageView = itemView.findViewById(R.id.ivLyricsStatus)
        val ivArtStatus: ImageView = itemView.findViewById(R.id.ivArtStatus)
        val ivStatusItemMenu: ImageView = itemView.findViewById(R.id.ivStatusItemMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatusViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyrics_art_status, parent, false)
        return StatusViewHolder(view)
    }

    override fun onBindViewHolder(holder: StatusViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.song.title
        holder.tvArtist.text = item.song.artist

        bindStatusIcon(holder.ivArtStatus, item.hasArt)
        bindStatusIcon(holder.ivLyricsStatus, item.hasLyrics)

        holder.itemView.setOnClickListener {
            onItemClick(items[holder.bindingAdapterPosition])
        }
        holder.ivStatusItemMenu.setOnClickListener {
            showItemMenu(holder.ivStatusItemMenu, items[holder.bindingAdapterPosition])
        }
    }

    private fun bindStatusIcon(imageView: ImageView, present: Boolean) {
        val context = imageView.context
        if (present) {
            imageView.setImageResource(R.drawable.ic_check)
            imageView.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.status_ok_green)
            )
        } else {
            imageView.setImageResource(R.drawable.ic_close)
            imageView.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.status_missing_gray)
            )
        }
    }

    /**
     * Reemplaza los dos iconos sueltos que habia antes (editar y
     * redescargar) por un solo boton de "mas opciones" que abre este
     * menu, siguiendo el mismo patron de PopupWindow que
     * TopBarController.showSortPopup() / popup_sort_menu.xml. Al tener
     * un unico boton grande en vez de dos pegados, ya no hay forma de
     * tocar uno por accidente en lugar del otro.
     */
    private fun showItemMenu(anchor: View, item: SongDownloadStatus) {
        val popupView = LayoutInflater.from(anchor.context)
            .inflate(R.layout.popup_status_item_menu, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.elevation = 16f

        popupView.findViewById<TextView>(R.id.tvStatusItemMenuEdit).setOnClickListener {
            onEditClick(item)
            popupWindow.dismiss()
        }
        popupView.findViewById<TextView>(R.id.tvStatusItemMenuRefresh).setOnClickListener {
            onForceRefreshClick(item)
            popupWindow.dismiss()
        }

        popupWindow.showAsDropDown(anchor, -220, 8)
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<SongDownloadStatus>) {
        items = newItems
        notifyDataSetChanged()
    }
}