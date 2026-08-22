package com.learnlayout.mp_3

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    initialSongs: List<Song>,
    private val onItemClick: (Int) -> Unit,
    private val onMenuClick: ((Int) -> Unit)? = null,
    // Solo PlaylistDetailActivity (en playlists propias, no en las
    // automaticas de historial) activa esto: muestra el handle de arrastre
    // y permite reordenar a mano.
    private val reorderable: Boolean = false,
    // Se llama cuando el usuario suelta una cancion en una posicion nueva,
    // para que quien use este adapter persista el nuevo orden.
    private val onMoveFinished: ((fromPosition: Int, toPosition: Int) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var songs: MutableList<Song> = initialSongs.toMutableList()

    private var currentPlayingId: Long? = null

    // Lo conecta quien use el adapter con un ItemTouchHelper.startDrag(...),
    // igual que ya hace QueueAdapter con la cola de reproduccion.
    var dragStartListener: ((RecyclerView.ViewHolder) -> Unit)? = null

    // Duracion del fade entre el placeholder (ic_music_note) y la caratula
    // real cuando llega de red/disco. Mismo criterio de suavidad que ya usa
    // PlayerPaletteTheme (ANIM_DURATION_MS) para las animaciones de color.
    private companion object {
        const val ALBUM_ART_FADE_MS = 220L
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAlbumArt: ImageView = itemView.findViewById(R.id.ivItemAlbumArt)
        val tvTitle: TextView = itemView.findViewById(R.id.tvItemTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvItemArtist)
        val btnMenu: ImageButton = itemView.findViewById(R.id.btnItemMenu)
        val ivDragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)

        // Padding original del placeholder (ic_music_note), para poder
        // restaurarlo cuando la caratula real no aplica o cambia el item.
        val albumArtBasePadding = intArrayOf(
            ivAlbumArt.paddingLeft,
            ivAlbumArt.paddingTop,
            ivAlbumArt.paddingRight,
            ivAlbumArt.paddingBottom
        )
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

        if (reorderable) {
            holder.ivDragHandle.visibility = View.VISIBLE
            holder.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    dragStartListener?.invoke(holder)
                }
                false
            }
        } else {
            holder.ivDragHandle.visibility = View.GONE
            holder.ivDragHandle.setOnTouchListener(null)
        }

        bindAlbumArt(holder, song)
    }

    private fun bindAlbumArt(holder: SongViewHolder, song: Song) {
        // El tag marca que cancion "espera" este ViewHolder. Si la vista se
        // recicla antes de que llegue la respuesta de red, el tag ya no
        // coincide y se descarta el bitmap para no pisar el item equivocado.
        holder.ivAlbumArt.tag = song.id
        holder.ivAlbumArt.animate().cancel()

        // Si la caratula ya esta en memoria (cancion ya vista antes en esta
        // sesion), se pinta directo sin pasar por el placeholder ni el
        // fade: eso es lo que evita el parpadeo icono->caratula al
        // reabrir la app o volver a scrollear por la lista.
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
            // Si mientras la tarea esperaba turno en el pool de hilos la
            // vista ya se reciclo para otra cancion (scroll rapido), se
            // descarta enseguida en vez de gastar un hilo en algo que ya
            // no se va a mostrar.
            isStillNeeded = { holder.ivAlbumArt.tag == song.id }
        )
    }

    // Pinta la caratula sin animacion: se usa cuando el bitmap ya esta en
    // cache de memoria y por lo tanto esta disponible en el mismo frame,
    // asi que un fade solo agregaria un parpadeo innecesario.
    private fun applyAlbumArtImmediate(holder: SongViewHolder, bitmap: Bitmap) {
        holder.ivAlbumArt.alpha = 1f
        holder.ivAlbumArt.setPadding(0, 0, 0, 0)
        holder.ivAlbumArt.imageTintList = null
        holder.ivAlbumArt.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.ivAlbumArt.setImageBitmap(bitmap)
    }

    // Reemplaza el placeholder por la caratula real con un fundido corto en
    // vez del salto abrupto de antes (setImageBitmap de golpe). Primero se
    // desvanece el placeholder a alpha 0, se cambia el bitmap ya invisible
    // (evita el "flash" del cambio a mitad de la animacion), y se vuelve a
    // aparecer con fade in.
    private fun applyAlbumArtWithFade(holder: SongViewHolder, song: Song, bitmap: Bitmap) {
        holder.ivAlbumArt.animate()
            .alpha(0f)
            .setDuration(ALBUM_ART_FADE_MS / 2)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // Si mientras se desvanecia la vista se reciclo para otra
                // cancion, no se pisa: se deja como quedo para el nuevo tag.
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

    fun updateData(newSongs: List<Song>) {
        songs = newSongs.toMutableList()
        notifyDataSetChanged()
    }

    fun setCurrentPlayingId(songId: Long?) {
        currentPlayingId = songId
        notifyDataSetChanged()
    }

    fun getSongAt(position: Int): Song = songs[position]

    fun getCurrentList(): List<Song> = songs

    /** Mueve visualmente una cancion mientras se arrastra (llamado desde el ItemTouchHelper.Callback en cada onMove). */
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in songs.indices || toPosition !in songs.indices) return
        val item = songs.removeAt(fromPosition)
        songs.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    /** Se llama cuando el usuario suelta el dedo tras arrastrar: aqui se dispara la persistencia del nuevo orden. */
    fun onDragFinished(fromPosition: Int, toPosition: Int) {
        if (fromPosition != toPosition) {
            onMoveFinished?.invoke(fromPosition, toPosition)
        }
    }
}