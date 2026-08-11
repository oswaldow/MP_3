package com.learnlayout.mp_3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid de opciones de caratula (ver AlbumArtPickerDialog). Cada item ya
 * trae el Bitmap descargado (no vuelve a pegarle a la red al hacer bind).
 */
class AlbumArtCandidateAdapter(
    private val candidates: List<AlbumArtRepository.AlbumArtCandidate>,
    private val onCandidateSelected: (AlbumArtRepository.AlbumArtCandidate) -> Unit
) : RecyclerView.Adapter<AlbumArtCandidateAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivArt: ImageView = view.findViewById(R.id.ivCandidateArt)
        val tvLabel: TextView = view.findViewById(R.id.tvCandidateLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album_art_candidate, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val candidate = candidates[position]
        holder.ivArt.setImageBitmap(candidate.bitmap)
        holder.tvLabel.text = candidate.sourceLabel
        holder.itemView.setOnClickListener {
            onCandidateSelected(candidate)
        }
    }

    override fun getItemCount(): Int = candidates.size
}