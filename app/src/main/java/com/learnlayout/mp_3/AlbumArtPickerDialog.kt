package com.learnlayout.mp_3

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Bottom sheet que se abre al mantener presionada la caratula del
 * reproductor expandido (ver PlayerPanelController.ivPanelAlbumArt
 * .setOnLongClickListener). Busca caratulas alternativas en iTunes/Deezer
 * para la cancion actual y, si el usuario elige una, la guarda como
 * override via AlbumArtRepository y avisa por [onCoverChosen].
 */
class AlbumArtPickerDialog(
    private val context: Context,
    private val song: Song,
    private val onCoverChosen: (Song, android.graphics.Bitmap) -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(context, R.style.RoundedBottomSheetDialog)
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_album_art_picker, null, false)
        dialog.setContentView(view)

        val subtitle = view.findViewById<TextView>(R.id.tvPickerSubtitle)
        subtitle.text = "Para \"${song.title}\" - ${song.artist}"

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
                    dialog.dismiss()
                }
            }
        })

        dialog.show()
    }
}