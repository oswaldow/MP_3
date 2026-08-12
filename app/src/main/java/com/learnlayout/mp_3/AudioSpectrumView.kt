package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * Visualizador de espectro de audio: dibuja barras verticales cuya
 * altura refleja la energia por banda de frecuencia del audio que esta
 * sonando, calculada en tiempo real por SpectrumAudioProcessor (metido
 * en la cadena de AudioProcessor de ExoPlayer, ver
 * EqAudioSinkRenderersFactory.buildAudioSink()).
 *
 * No mantiene hilo ni Handler propio: mientras esta "started" se
 * reengancha a Choreographer despues de cada frame dibujado, asi que
 * solo gasta CPU cuando esta realmente en pantalla. Quien controla el
 * ciclo de vida (start()/stop()) es PlayerPanelController, al expandir
 * o colapsar el panel del reproductor.
 */
class AudioSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val barSpacingPx = 3f * density
    private val minBarHeightPx = 3f * density
    private val cornerRadiusPx = 2.5f * density

    // Colores por defecto (blanco -> blanco translucido de arriba a
    // abajo), consistentes con la paleta del panel expandido
    // (WaveformSeekBar usa el mismo criterio: blanco fijo, no tenido por
    // Material You). Se pueden pisar con setBarColors() si hace falta
    // que sigan el acento de la caratula (PlayerPanelController.getAccentColor()).
    private var colorTop = Color.WHITE
    private var colorBottom = Color.argb(102, 255, 255, 255) // #66FFFFFF

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Copia local de trabajo: se rellena desde SpectrumAudioProcessor en
    // cada frame y es lo unico que onDraw lee, para no tener que
    // sincronizar con el hilo de audio en medio del dibujo.
    private var levels = FloatArray(SpectrumAudioProcessor.bandCount())

    private var running = false

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback {
        if (!running) return@FrameCallback
        pullLatestData()
        invalidate()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    init {
        applyGradientIfPossible()
    }

    /** Arranca el loop de refresco (idempotente). */
    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Para el loop de refresco y deja las barras en 0, para que la
     * proxima vez que se abra el panel no se vea "congelada" en la
     * ultima forma que tenia el audio.
     */
    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        levels = FloatArray(levels.size)
        invalidate()
    }

    /**
     * Permite que quien arme la UI tinte las barras con el color de
     * acento (Material You) extraido de la caratula actual, en vez del
     * blanco por defecto.
     */
    fun setBarColors(top: Int, bottom: Int) {
        colorTop = top
        colorBottom = bottom
        applyGradientIfPossible()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Ahorro extra: si la vista queda oculta por fuera del ciclo
        // expand/collapse del panel (p.ej. la Activity entera se va a
        // background), no seguimos pidiendo frames.
        if (visibility != VISIBLE) stop()
    }

    private fun pullLatestData() {
        val source = SpectrumAudioProcessor.getMagnitudes()
        if (levels.size != source.size) {
            levels = FloatArray(source.size)
        }
        System.arraycopy(source, 0, levels, 0, source.size)
    }

    private fun applyGradientIfPossible() {
        if (width > 0 && height > 0) {
            barPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                colorTop, colorBottom,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyGradientIfPossible()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bands = levels
        if (bands.isEmpty()) return

        val usableWidth = width - paddingLeft - paddingRight
        val usableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (usableWidth <= 0 || usableHeight <= 0) return

        val barWidthPx = (usableWidth - barSpacingPx * (bands.size - 1)) / bands.size
        if (barWidthPx <= 0f) return

        val bottom = height - paddingBottom.toFloat()
        var x = paddingLeft.toFloat()

        for (level in bands) {
            val barHeight = (level * usableHeight).coerceAtLeast(minBarHeightPx)
            val top = bottom - barHeight
            canvas.drawRoundRect(
                x, top, x + barWidthPx, bottom,
                cornerRadiusPx, cornerRadiusPx, barPaint
            )
            x += barWidthPx + barSpacingPx
        }
    }
}