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
 * ciclo de vida deseado (start()/stop()) es PlayerPanelController, al
 * expandir o colapsar el panel del reproductor.
 *
 * IMPORTANTE (fix bug "barras se congelan al volver de apps
 * recientes"): la vista tambien se pausa/reanuda sola por su propia
 * visibilidad (onVisibilityChanged/onWindowVisibilityChanged), sin que
 * PlayerPanelController se entere ni vuelva a llamar start(). Por eso
 * se separan dos conceptos:
 *  - wantsToRun: el estado DESEADO, controlado solo por start()/stop()
 *    externos (PlayerPanelController). Se mantiene aunque la vista se
 *    oculte temporalmente.
 *  - running: si el loop de Choreographer esta activo AHORA MISMO.
 *    Se apaga cuando la vista/ventana deja de ser visible (para no
 *    gastar CPU) y se reenciende solo, sin intervencion externa,
 *    apenas la vista vuelve a ser visible, siempre que wantsToRun
 *    siga en true.
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

    // Estado deseado: lo fija start()/stop(), llamados desde
    // PlayerPanelController al expandir/colapsar el panel. Sobrevive a
    // que la vista se oculte y reaparezca por otros motivos (apps
    // recientes, Home, etc).
    private var wantsToRun = false

    // Estado real del loop en este momento (¿hay un frameCallback
    // efectivamente encolado?).
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

    /** Arranca el loop de refresco (idempotente). Fija el estado deseado. */
    fun start() {
        wantsToRun = true
        resumeIfPossible()
    }

    /**
     * Para el loop de refresco y deja las barras en 0, para que la
     * proxima vez que se abra el panel no se vea "congelada" en la
     * ultima forma que tenia el audio. Fija el estado deseado: no se
     * va a reactivar solo hasta el proximo start().
     */
    fun stop() {
        wantsToRun = false
        pauseInternal(resetLevels = true)
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
        // Al desengancharse de verdad (Activity destruida, etc.) si o
        // si se corta todo, incluido el estado deseado.
        wantsToRun = false
        pauseInternal(resetLevels = true)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Esto es lo que se dispara al ir a la pantalla de apps
        // recientes (antes incluso que onWindowVisibilityChanged). NO
        // tocamos wantsToRun aqui: solo pausamos/reanudamos el loop
        // real segun corresponda.
        if (visibility == VISIBLE) {
            resumeIfPossible()
        } else {
            pauseInternal(resetLevels = true)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Red de seguridad adicional por si en algun dispositivo/version
        // de Android la ventana cambia de visibilidad sin que se dispare
        // onVisibilityChanged para esta vista.
        if (visibility == VISIBLE) {
            resumeIfPossible()
        } else {
            pauseInternal(resetLevels = false)
        }
    }

    /** Reanuda el loop real solo si el estado deseado lo pide y no esta ya corriendo. */
    private fun resumeIfPossible() {
        if (!wantsToRun || running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Pausa el loop real (sin tocar el estado deseado wantsToRun). */
    private fun pauseInternal(resetLevels: Boolean) {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (resetLevels) {
            levels = FloatArray(levels.size)
            invalidate()
        }
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