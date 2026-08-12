package com.learnlayout.mp_3

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Bottom sheet que se abre al mantener presionada la caratula del
 * reproductor expandido (ver PlayerPanelController.ivPanelAlbumArt
 * .setOnLongClickListener). Es el UNICO lugar donde la app busca en red
 * caratulas y letras alternativas mientras se esta escuchando musica (ver
 * AlbumArtRepository.loadCoverCacheOnly / LyricsPanelController.loadForSong,
 * que ya no lo hacen automaticamente).
 *
 * Busca caratulas en iTunes/Deezer y letras en LRCLIB para la cancion
 * actual. Si el usuario elige una caratula, se guarda como override via
 * AlbumArtRepository y se avisa por [onCoverChosen]. Si elige una letra, se
 * guarda en SavedLyricsRepository y se avisa por [onLyricsChosen].
 */
class AlbumArtPickerDialog(
    private val context: Context,
    private val song: Song,
    private val onCoverChosen: (Song, android.graphics.Bitmap) -> Unit,
    private val onLyricsChosen: (Song, LyricsResult) -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(context, R.style.RoundedBottomSheetDialog)
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_album_art_picker, null, false)
        dialog.setContentView(view)

        val subtitle = view.findViewById<TextView>(R.id.tvPickerSubtitle)
        subtitle.text = "Para \"${song.title}\" - ${song.artist}  •  Duración: ${formatDuration(song.duration)}"

        setupArtSection(view)
        setupLyricsSection(view, dialog)

        dialog.show()
    }

    // ---------- Caratulas ----------

    private fun setupArtSection(view: View) {
        val progress = view.findViewById<ProgressBar>(R.id.progressCandidates)
        val empty = view.findViewById<TextView>(R.id.tvCandidatesEmpty)
        val recycler = view.findViewById<RecyclerView>(R.id.rvCandidates)
        recycler.layoutManager = GridLayoutManager(context, 3)

        AlbumArtRepository.searchCandidates(song, object : AlbumArtRepository.CandidatesCallback {
            override fun onCandidatesReady(candidates: List<AlbumArtRepository.AlbumArtCandidate>) {
                progress.visibility = View.GONE

                if (candidates.isEmpty()) {
                    empty.visibility = View.VISIBLE
                    return
                }

                recycler.visibility = View.VISIBLE
                recycler.adapter = AlbumArtCandidateAdapter(candidates) { chosen ->
                    onCoverChosen(song, chosen.bitmap)
                }
            }
        })
    }

    // ---------- Letras ----------

    private fun setupLyricsSection(view: View, dialog: BottomSheetDialog) {
        val progress = view.findViewById<ProgressBar>(R.id.progressLyricsCandidates)
        val empty = view.findViewById<TextView>(R.id.tvLyricsCandidatesEmpty)
        val recycler = view.findViewById<RecyclerView>(R.id.rvLyricsCandidates)
        recycler.layoutManager = LinearLayoutManager(context)

        LyricsRepository.searchCandidates(song.title, song.artist, object : LyricsRepository.LyricsCandidatesCallback {
            override fun onCandidatesReady(candidates: List<LyricsCandidate>) {
                progress.visibility = View.GONE

                if (candidates.isEmpty()) {
                    empty.visibility = View.VISIBLE
                    return
                }

                recycler.visibility = View.VISIBLE
                recycler.adapter = LyricsCandidateAdapter(
                    candidates = candidates,
                    referenceDurationSeconds = song.duration / 1000L
                ) { chosen ->
                    onLyricsChosen(song, chosen.result)
                    dialog.dismiss()
                }
            }
        })
    }
    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}