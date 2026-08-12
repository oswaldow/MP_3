package com.learnlayout.mp_3

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.core.content.ContextCompat

/**
 * Se encarga exclusivamente de cargar y pintar la carátula del encabezado
 * de la cola. QueueSheetController no necesita conocer los detalles de
 * cache, placeholders ni callbacks asíncronos.
 */
class QueueSheetArtworkController(
    private val activity: android.content.Context
) {

    fun refresh(
        imageView: ImageView?,
        song: Song?
    ) {
        val view = imageView ?: return

        if (song == null) {
            showPlaceholder(view)
            view.tag = null
            return
        }

        view.tag = song.id

        AlbumArtRepository.getCachedCover(song)?.let { cached ->
            applyCover(view, cached)
            return
        }

        showPlaceholder(view)

        AlbumArtRepository.loadCover(
            context = activity,
            song = song,
            callback = object : AlbumArtRepository.Callback {
                override fun onCoverReady(bitmap: Bitmap) {
                    if (view.tag != song.id) return
                    applyCover(view, bitmap)
                }
            },
            isStillNeeded = {
                view.tag == song.id
            }
        )
    }

    private fun showPlaceholder(view: ImageView) {
        view.setImageResource(R.drawable.ic_music_note)
        val padding = dp(12)
        view.setPadding(padding, padding, padding, padding)
        view.imageTintList =
            ContextCompat.getColorStateList(activity, R.color.spotify_gray)
        view.scaleType = ImageView.ScaleType.CENTER
    }

    private fun applyCover(
        view: ImageView,
        bitmap: Bitmap
    ) {
        view.setPadding(0, 0, 0, 0)
        view.imageTintList = null
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bitmap)
    }

    private fun dp(value: Int): Int {
        return (
                value * activity.resources.displayMetrics.density
                ).toInt()
    }
}