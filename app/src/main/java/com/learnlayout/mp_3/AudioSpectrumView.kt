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
 *
 * IMPORTANTE (fix bug "barras se ven a los tirones con archivos
 * hi-res/FLAC"): antes se leia "el ultimo valor calculado" en
 * SpectrumAudioProcessor una vez por frame de UI. El problema es que
 * el decoder no entrega el PCM parejo: un paquete grande (tipico de
 * FLAC hi-res) puede disparar 3-4 calculos nuevos casi de una, y como
 * antes solo existia "el ultimo valor", los primeros 3 se perdian y
 * las barras se quedaban clavadas ~100ms para despues saltar de golpe.
 * Ahora se consume la cola de snapshots de SpectrumAudioProcessor a un
 * ritmo pautado (SpectrumAudioProcessor.getUpdateIntervalMs(), tipico
 * ~23ms), sacando un snapshot nuevo por tick en vez de vaciar la cola
 * entera de una, sin importar si el frame de UI corre mas rapido (60,
 * 90 o 120fps) que la llegada de datos nuevos.
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

    // Copia local de trabajo: se actualiza a ritmo pautado desde la cola
    // de SpectrumAudioProcessor (ver pullPacedSnapshot) y es lo unico
    // que onDraw lee, para no tener que sincronizar con el hilo de audio
    // en medio del dibujo.
    private var levels = FloatArray(SpectrumAudioProcessor.bandCount())

    // Marca de tiempo (nanos de Choreographer) del ultimo snapshot
    // efectivamente consumido de la cola. 0L significa "todavia no se
    // consumio ninguno desde el ultimo start()/resume".
    private var lastSnapshotPullNs = 0L

    // Estado deseado: lo fija start()/stop(), llamados desde
    // PlayerPanelController al expandir/colapsar el panel. Sobrevive a
    // que la vista se oculte y reaparezca por otros motivos (apps
    // recientes, Home, etc).
    private var wantsToRun = false

    // Estado real del loop en este momento (¿hay un frameCallback
    // efectivamente encolado?).
    private var running = false

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNs ->
        if (!running) return@FrameCallback
        pullPacedSnapshot(frameTimeNs)
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
        // Arranca el pauteo de cero: el primer tick despues de reanudar
        // consume un snapshot apenas haya uno disponible.
        lastSnapshotPullNs = 0L
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

    /**
     * Saca como maximo un snapshot nuevo de la cola de
     * SpectrumAudioProcessor por cada intervalo "natural" de actualizacion
     * (getUpdateIntervalMs()), sin importar cuantos frames de UI pasen
     * mientras tanto. Asi, si llegaron varios snapshots juntos (paquete
     * grande de PCM, tipico de FLAC hi-res), se van consumiendo de a uno
     * a ritmo parejo en vez de perderse todos menos el ultimo.
     */
    private fun pullPacedSnapshot(frameTimeNs: Long) {
        val intervalNs = SpectrumAudioProcessor.getUpdateIntervalMs().coerceAtLeast(1L) * 1_000_000L
        if (lastSnapshotPullNs != 0L && frameTimeNs - lastSnapshotPullNs < intervalNs) {
            return
        }
        val snapshot = SpectrumAudioProcessor.pollNextSnapshot() ?: return
        if (levels.size != snapshot.size) {
            levels = FloatArray(snapshot.size)
        }
        System.arraycopy(snapshot, 0, levels, 0, snapshot.size)
        lastSnapshotPullNs = frameTimeNs
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