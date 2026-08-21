package com.learnlayout.mp_3

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Adaptador de la pantalla "Agregar canciones" (AddSongsToPlaylistActivity).
 *
 * Es practicamente un gemelo de SongAdapter (misma logica de carga de
 * caratula con fade), pero en vez del boton de 3 puntos cada fila
 * muestra un circulo de seleccion: vacio si la cancion no esta elegida,
 * relleno de morado con un check si si. Tocar la fila completa alterna
 * la seleccion, no abre el reproductor.
 */
class SelectableSongAdapter(
    private var songs: List<Song>,
    private val isSelected: (Long) -> Boolean,
    private val onToggle: (Song) -> Unit
) : RecyclerView.Adapter<SelectableSongAdapter.SongViewHolder>() {

    private companion object {
        const val ALBUM_ART_FADE_MS = 220L
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAlbumArt: ImageView = itemView.findViewById(R.id.ivItemAlbumArt)
        val tvTitle: TextView = itemView.findViewById(R.id.tvItemTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvItemArtist)
        val selectionCircle: View = itemView.findViewById(R.id.viewSelectionCircle)
        val ivCheck: ImageView = itemView.findViewById(R.id.ivSelectionCheck)

        val albumArtBasePadding = intArrayOf(
            ivAlbumArt.paddingLeft,
            ivAlbumArt.paddingTop,
            ivAlbumArt.paddingRight,
            ivAlbumArt.paddingBottom
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song_selectable, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist

        val selected = isSelected(song.id)

        holder.itemView.setBackgroundResource(
            if (selected) R.drawable.bg_item_song_playing else R.drawable.bg_item_song
        )

        holder.selectionCircle.setBackgroundResource(
            if (selected) R.drawable.bg_selection_circle_filled else R.drawable.bg_selection_circle_empty
        )

        holder.ivCheck.visibility = if (selected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onToggle(song) }

        bindAlbumArt(holder, song)
    }

    private fun bindAlbumArt(holder: SongViewHolder, song: Song) {
        holder.ivAlbumArt.tag = song.id
        holder.ivAlbumArt.animate().cancel()

        val cached = AlbumArtRepository.getCachedCover(song)
        if (cached != null) {
            applyAlbumArtImmediate(holder, cached)
            return
        }

        showPlaceholder(holder)

        AlbumArtRepository.loadCover(
            context = holder.itemView.context,
            song = song,
            callback = object : AlbumArtRepository.Callback {
                override fun onCoverReady(bitmap: Bitmap) {
                    if (holder.ivAlbumArt.tag != song.id) return
                    applyAlbumArtWithFade(holder, song, bitmap)
                }
            },
            isStillNeeded = { holder.ivAlbumArt.tag == song.id }
        )
    }

    private fun applyAlbumArtImmediate(holder: SongViewHolder, bitmap: Bitmap) {
        holder.ivAlbumArt.alpha = 1f
        holder.ivAlbumArt.setPadding(0, 0, 0, 0)
        holder.ivAlbumArt.imageTintList = null
        holder.ivAlbumArt.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.ivAlbumArt.setImageBitmap(bitmap)
    }

    private fun applyAlbumArtWithFade(holder: SongViewHolder, song: Song, bitmap: Bitmap) {
        holder.ivAlbumArt.animate()
            .alpha(0f)
            .setDuration(ALBUM_ART_FADE_MS / 2)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (holder.ivAlbumArt.tag != song.id) return@withEndAction

                holder.ivAlbumArt.setPadding(0, 0, 0, 0)
                holder.ivAlbumArt.imageTintList = null
                holder.ivAlbumArt.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.ivAlbumArt.setImageBitmap(bitmap)

                holder.ivAlbumArt.animate()
                    .alpha(1f)
                    .setDuration(ALBUM_ART_FADE_MS / 2)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun showPlaceholder(holder: SongViewHolder) {
        holder.ivAlbumArt.alpha = 1f
        val padding = holder.albumArtBasePadding
        holder.ivAlbumArt.setPadding(padding[0], padding[1], padding[2], padding[3])
        holder.ivAlbumArt.scaleType = ImageView.ScaleType.FIT_CENTER
        holder.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
        holder.ivAlbumArt.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(holder.itemView.context, R.color.spotify_gray)
        )
    }

    override fun getItemCount(): Int = songs.size

    /**
     * Actualiza la lista completa. Se usa tanto al filtrar por busqueda
     * como para refrescar los circulos de seleccion despues de un
     * toggle (notifyDataSetChanged es suficiente aqui, la lista nunca
     * es tan larga como para justificar DiffUtil).
     */
    fun updateData(newSongs: List<Song>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    fun refreshSelectionStates() {
        notifyDataSetChanged()
    }
}