package com.learnlayout.mp_3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Lista de opciones de letra (ver AlbumArtPickerDialog). Cada item ya
 * trae el LyricsResult completo (no vuelve a pegarle a la red al elegirlo).
 */
class LyricsCandidateAdapter(
    private val candidates: List<LyricsCandidate>,
    private val onCandidateSelected: (LyricsCandidate) -> Unit
) : RecyclerView.Adapter<LyricsCandidateAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tvLyricsCandidateLabel)
        val tvPreview: TextView = view.findViewById(R.id.tvLyricsCandidatePreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyrics_candidate, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val candidate = candidates[position]
        holder.tvLabel.text = candidate.label

        val preview = candidate.result.plainLyrics
            ?: candidate.result.syncedLines?.firstOrNull { it.text.isNotBlank() }?.text
            ?: ""
        holder.tvPreview.text = preview.lineSequence().firstOrNull { it.isNotBlank() } ?: ""

        holder.itemView.setOnClickListener {
            onCandidateSelected(candidate)
        }
    }

    override fun getItemCount(): Int = candidates.size
}