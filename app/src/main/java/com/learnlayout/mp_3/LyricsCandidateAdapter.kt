package com.learnlayout.mp_3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Locale
import androidx.recyclerview.widget.RecyclerView

/**
 * Lista de opciones de letra (ver AlbumArtPickerDialog). Cada item ya
 * trae el LyricsResult completo (no vuelve a pegarle a la red al elegirlo).
 */
class LyricsCandidateAdapter(
    private val candidates: List<LyricsCandidate>,
    private val referenceDurationSeconds: Long,
    private val onCandidateSelected: (LyricsCandidate) -> Unit
) : RecyclerView.Adapter<LyricsCandidateAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tvLyricsCandidateLabel)
        val tvPreview: TextView = view.findViewById(R.id.tvLyricsCandidatePreview)
        val tvDuration: TextView = view.findViewById(R.id.tvLyricsCandidateDuration)
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

        holder.tvDuration.text = buildDurationText(candidate.durationSeconds)

        holder.itemView.setOnClickListener {
            onCandidateSelected(candidate)
        }
    }

    private fun buildDurationText(candidateSeconds: Long?): String {
        val songLabel = formatDuration(referenceDurationSeconds)
        if (candidateSeconds == null || candidateSeconds <= 0L) {
            return "Duración de la letra: —  •  Tu canción: $songLabel"
        }

        val difference = kotlin.math.abs(candidateSeconds - referenceDurationSeconds)
        val candidateLabel = formatDuration(candidateSeconds)
        val differenceLabel = formatDuration(difference)

        val matchLabel = when {
            difference <= 2L -> "✓ Coincide"
            difference <= 5L -> "≈ Muy cercana"
            else -> "Δ $differenceLabel"
        }

        return "Letra: $candidateLabel  •  Tu canción: $songLabel  •  $matchLabel"
    }

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds.coerceAtLeast(0L) / 60L
        val seconds = totalSeconds.coerceAtLeast(0L) % 60L
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun getItemCount(): Int = candidates.size
}