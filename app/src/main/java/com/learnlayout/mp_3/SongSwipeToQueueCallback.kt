package com.learnlayout.mp_3

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SongSwipeToQueueCallback(
    private val onSwipeToPlayNext: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    // Por defecto ItemTouchHelper pide cruzar el 50% del ancho de la fila
    // para que el swipe "cuente" por distancia. Con logs de diagnostico
    // se vio que incluso al 28% (valor anterior) un swipe lento de ~18%
    // de recorrido se regresaba sin avisar. Bajado a 20% para que baste
    // con un recorrido corto y notorio.
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.20f

    // Ademas del umbral por distancia, ItemTouchHelper completa el swipe
    // si el dedo se suelta con suficiente velocidad (un "flick"), sin
    // importar que tan lejos haya llegado. Los logs mostraron que con la
    // mitad del default (0.5x) la mayoria de los swipes normales ya
    // completaban por velocidad; se baja un poco mas (0.35x) para cubrir
    // tambien los mas lentos/cortos.
    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 0.35f

    override fun getSwipeVelocityThreshold(defaultValue: Float): Float = defaultValue * 0.5f

    // La animacion por defecto (tanto la de "volar" hacia afuera al
    // completar el swipe, como la de regresar a su lugar al cancelarlo)
    // tarda lo mismo que la del ItemAnimator del RecyclerView, lo que se
    // sentia lento/pegajoso al final del gesto. Se acorta a un valor fijo
    // mas rapido para que se sienta inmediato.
    override fun getAnimationDuration(
        recyclerView: RecyclerView,
        animationType: Int,
        animateDx: Float,
        animateDy: Float
    ): Long {
        return when (animationType) {
            ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS -> 150L
            ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL -> 150L
            else -> super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
        }
    }

    // La cancion se agrega a la cola pero se queda en la lista, asi que se
    // regresa a su posicion original con notifyItemChanged en vez de
    // dejar que ItemTouchHelper la elimine visualmente.
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        onSwipeToPlayNext(position)
        viewHolder.bindingAdapter?.notifyItemChanged(position)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0) {
            val itemView = viewHolder.itemView
            val context = itemView.context
            val density = context.resources.displayMetrics.density

            val background = Paint().apply {
                color = ContextCompat.getColor(context, R.color.purple_primary)
                isAntiAlias = true
            }
            val cornerRadius = 16f * density
            val rect = RectF(
                itemView.left.toFloat(),
                itemView.top.toFloat(),
                dX,
                itemView.bottom.toFloat()
            )
            c.drawRoundRect(rect, cornerRadius, cornerRadius, background)

            val icon = ContextCompat.getDrawable(context, R.drawable.ic_queue_music)
            if (icon != null) {
                val iconSize = (24 * density).toInt()
                val iconTop = itemView.top + (itemView.height - iconSize) / 2
                val iconLeft = itemView.left + (itemView.height - iconSize) / 2
                icon.setTint(ContextCompat.getColor(context, R.color.spotify_black))
                icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                icon.draw(c)
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}