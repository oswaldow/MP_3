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
 *
 * IMPORTANTE (fix bug "el visualizador va atrasado, no en tiempo
 * real"): consumir siempre "de a uno, en orden" tenia un efecto
 * secundario: si esta vista se atrasaba aunque sea una vez (jank de
 * UI, decodificar la caratula, GC, o simplemente estar detenida
 * mientras el panel estaba colapsado y el audio seguia sonando), el
 * backlog de snapshots viejos en la cola JAMAS se recuperaba solo,
 * porque el ritmo de consumo nunca era mas rapido que el de
 * produccion. El resultado: un atraso que se quedaba pegado para
 * siempre despues de la primera interrupcion. Ahora:
 *  - resumeIfPossible() vacia la cola (SpectrumAudioProcessor.clearQueue())
 *    antes de arrancar, para no consumir snapshots de antes de la pausa.
 *  - pullPacedSnapshot() descarta snapshots viejos cuando el backlog es
 *    mayor al que produciria un burst normal de FLAC hi-res (ver
 *    MAX_EXPECTED_BURST_BACKLOG), en vez de arrastrarlos. Un burst
 *    chico (3-6 snapshots de un paquete grande) se sigue consumiendo
 *    de a uno para que se vea parejo, pero un backlog mas grande que
 *    eso ya no es un burst normal: es que la UI se atraso de verdad, y
 *    ahi conviene saltar directo al dato mas reciente.
 */
class AudioSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Cuantos snapshots pendientes en la cola se consideran todavia
        // "un burst normal" (tipico de FLAC hi-res, ver comentario de
        // SpectrumAudioProcessor). Mas que esto ya no es un burst del
        // decoder, es que el consumidor se atraso de verdad: en ese caso
        // se descartan los mas viejos para ponerse al dia de una.
        private const val MAX_EXPECTED_BURST_BACKLOG = 6
    }

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
        // Vacia cualquier backlog que se haya acumulado mientras esta
        // vista estaba parada (panel colapsado, vista oculta, etc.): sin
        // esto, al reanudar se arrancaba consumiendo snapshots viejos de
        // antes de la pausa en vez del audio actual.
        SpectrumAudioProcessor.clearQueue()
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
     *
     * Si el backlog pendiente es mayor al que produciria un burst normal
     * del decoder (MAX_EXPECTED_BURST_BACKLOG), ya no es un burst: es que
     * este consumidor se atraso de verdad (jank, pausa, etc.), y en ese
     * caso se descartan los snapshots mas viejos para ponerse al dia de
     * una, en vez de arrastrar ese atraso indefinidamente.
     */
    private fun pullPacedSnapshot(frameTimeNs: Long) {
        val intervalNs = SpectrumAudioProcessor.getUpdateIntervalMs().coerceAtLeast(1L) * 1_000_000L
        if (lastSnapshotPullNs != 0L && frameTimeNs - lastSnapshotPullNs < intervalNs) {
            return
        }

        var pending = SpectrumAudioProcessor.pendingSnapshotCount()
        while (pending > MAX_EXPECTED_BURST_BACKLOG) {
            SpectrumAudioProcessor.pollNextSnapshot()
            pending--
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
        val bandCount = levels.size
        if (bandCount == 0 || width <= 0 || height <= 0) return

        val totalSpacing = barSpacingPx * (bandCount - 1)
        val barWidth = (width - totalSpacing) / bandCount
        if (barWidth <= 0f) return

        var x = 0f
        for (i in 0 until bandCount) {
            val level = levels[i].coerceIn(0f, 1f)
            val barHeight = (level * height).coerceAtLeast(minBarHeightPx)
            val top = height - barHeight
            canvas.drawRoundRect(
                x, top, x + barWidth, height.toFloat(),
                cornerRadiusPx, cornerRadiusPx,
                barPaint
            )
            x += barWidth + barSpacingPx
        }
    }
}