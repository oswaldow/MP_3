package com.learnlayout.mp_3

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistAdapter(
    private var playlists: List<Playlist>,
    private val getSongsForPlaylist: (Playlist) -> List<Song>,
    private val onAddNewClick: () -> Unit,
    private val onItemClick: (Playlist) -> Unit,
    private val onDeleteClick: (Playlist) -> Unit,
    private val onCoverClick: (Playlist) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ADD_NEW = 0
        private const val TYPE_PLAYLIST = 1
    }

    inner class AddNewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivCover: ImageView = itemView.findViewById(R.id.ivPlaylistCover)
        val btnEditCover: ImageButton = itemView.findViewById(R.id.btnEditCover)
        val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
        val tvCount: TextView = itemView.findViewById(R.id.tvPlaylistCount)
        val tvSong1: TextView = itemView.findViewById(R.id.tvPlaylistSong1)
        val tvSong2: TextView = itemView.findViewById(R.id.tvPlaylistSong2)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeletePlaylist)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) TYPE_ADD_NEW else TYPE_PLAYLIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_ADD_NEW) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_playlist_add_new, parent, false)
            AddNewViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_playlist, parent, false)
            PlaylistViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddNewViewHolder) {
            holder.itemView.setOnClickListener { onAddNewClick() }
            return
        }

        if (holder is PlaylistViewHolder) {
            val playlist = playlists[position - 1]
            holder.tvName.text = playlist.name

            val count = playlist.songIds.size
            holder.tvCount.text = count.toString()

            val songs = getSongsForPlaylist(playlist)
            holder.tvSong1.text = songs.getOrNull(0)?.title ?: "Sin canciones"
            holder.tvSong2.text = songs.getOrNull(1)?.title ?: ""
            holder.tvSong2.visibility = if (songs.size > 1) View.VISIBLE else View.GONE

            val cover = playlist.coverImageUri
            if (cover != null) {
                try {
                    holder.ivCover.setPadding(0, 0, 0, 0)
                    holder.ivCover.setImageURI(Uri.parse(cover))
                } catch (e: Exception) {
                    holder.ivCover.setImageResource(R.drawable.ic_queue_music)
                }
            } else if (playlist.id == PlaylistRepository.FAVORITES_PLAYLIST_ID) {
                holder.ivCover.setPadding(16, 16, 16, 16)
                holder.ivCover.setImageResource(R.drawable.ic_favorite)
            } else {
                holder.ivCover.setImageResource(R.drawable.ic_queue_music)
            }

            holder.itemView.setOnClickListener { onItemClick(playlist) }
            holder.btnEditCover.setOnClickListener { onCoverClick(playlist) }

            if (playlist.id == PlaylistRepository.FAVORITES_PLAYLIST_ID) {
                holder.btnDelete.visibility = View.GONE
                holder.btnDelete.setOnClickListener(null)
            } else {
                holder.btnDelete.visibility = View.VISIBLE
                holder.btnDelete.setOnClickListener { onDeleteClick(playlist) }
            }
        }
    }

    override fun getItemCount(): Int = playlists.size + 1

    fun updateData(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
}