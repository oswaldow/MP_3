package com.learnlayout.mp_3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private var songs: List<Song>,
    private val onItemClick: (Int) -> Unit,
    private val onMenuClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var currentPlayingId: Long? = null

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvItemTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvItemArtist)
        val btnMenu: ImageButton = itemView.findViewById(R.id.btnItemMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist

        val isPlaying = song.id == currentPlayingId
        holder.itemView.setBackgroundResource(
            if (isPlaying) R.drawable.bg_item_song_playing else R.drawable.bg_item_song
        )

        holder.itemView.setOnClickListener { onItemClick(position) }

        if (onMenuClick != null) {
            holder.btnMenu.visibility = View.VISIBLE
            holder.btnMenu.setOnClickListener { onMenuClick.invoke(position) }
        } else {
            holder.btnMenu.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = songs.size

    fun updateData(newSongs: List<Song>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    fun setCurrentPlayingId(songId: Long?) {
        currentPlayingId = songId
        notifyDataSetChanged()
    }

    fun getSongAt(position: Int): Song = songs[position]

    fun getCurrentList(): List<Song> = songs
}