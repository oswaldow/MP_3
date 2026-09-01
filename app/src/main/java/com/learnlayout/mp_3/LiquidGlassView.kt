package com.learnlayout.mp_3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Panel con efecto "liquid glass": toma una foto del fondo animado que
 * queda detras suyo (ver [AmbientBackgroundController]), la difumina y
 * la dibuja como su propio fondo, con un velo translucido encima y un
 * borde con gradiente de brillo para simular vidrio esmerilado.
 *
 * El difuminado NO se recalcula en cada frame de la animacion de
 * destellos: se toma una sola foto y se vuelve a tomar solo cuando
 * cambia la paleta de color de fondo (cancion nueva), via
 * [refreshGlass]. Por eso el blur es por software (funciona igual en
 * Android 24+ sin depender de RenderEffect/API 31): al ser una unica
 * foto congelada y no un blur en vivo, un blur por CPU se ve igual de
 * bien y evita el costo de recalcular en cada frame.
 *
 * Se usa como reemplazo directo de un LinearLayout normal: conserva
 * orientation/gravity/padding definidos en el XML, solo cambia como se
 * pinta el fondo (ya no lleva android:background).
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_CORNER_RADIUS_DP = 22f
        private const val BLUR_DOWNSAMPLE = 0.22f
        private const val BLUR_RADIUS = 9
        private const val OVERLAY_ALPHA = 34
        private const val BORDER_ALPHA_TOP = 110
        private const val BORDER_ALPHA_BOTTOM = 30
    }

    private val density = context.resources.displayMetrics.density

    // Radio de esquina POR INSTANCIA (antes era fijo para todos los
    // paneles). Por defecto sigue siendo DEFAULT_CORNER_RADIUS_DP (los
    // accesos rapidos del Home, etc), pero se puede bajar a 0 con
    // setCornerRadiusDp() para paneles que deben cubrir por completo un
    // fondo rectangular detras suyo (ver groupMini en SongListActivity):
    // con radio 0 (esquinas rectas) el rectangulo pintado siempre cubre
    // por completo cualquier drawable redondeado que quede debajo, sin
    // depender de que los radios de ambos coincidan exactamente.
    private var cornerRadiusPx = DEFAULT_CORNER_RADIUS_DP * density

    private var backdropSource: View? = null
    private var blurredBitmap: Bitmap? = null

    private val clipPath = Path()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(OVERLAY_ALPHA, 255, 255, 255)
    }

    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#171717")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    init {
        setWillNotDraw(false)
    }


    // ============================================================
    // API PUBLICA
    // ============================================================

    /**
     * Conecta este panel con la vista que tiene el fondo animado
     * (gradiente + destellos) para poder "fotografiarlo". Se llama una
     * sola vez, normalmente la misma vista pasada como backgroundTarget
     * a [AmbientBackgroundController].
     */
    fun attachBackdrop(source: View) {
        backdropSource = source
        post { refreshGlass() }
    }

    /**
     * Vuelve a tomar la foto del fondo y la difumina. Llamar cada vez
     * que el fondo animado cambia de color (cancion nueva) para que el
     * vidrio refleje el color actual.
     */
    fun refreshGlass() {

        val source = backdropSource
        if (source == null) {
            Log.d("MP3_LiquidGlassDebug", "${debugName()}: refreshGlass() abortado, backdropSource es null")
            return
        }

        val drawable = source.background
        if (drawable == null) {
            Log.d("MP3_LiquidGlassDebug", "${debugName()}: refreshGlass() abortado, source.background es null")
            return
        }

        if (width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) {
            Log.d(
                "MP3_LiquidGlassDebug",
                "${debugName()}: refreshGlass() abortado, tamaños invalidos " +
                        "(self=${width}x$height, source=${source.width}x${source.height})"
            )
            return
        }

        val sourceLoc = IntArray(2)
        val selfLoc = IntArray(2)
        source.getLocationOnScreen(sourceLoc)
        getLocationOnScreen(selfLoc)

        val offsetX = selfLoc[0] - sourceLoc[0]
        val offsetY = selfLoc[1] - sourceLoc[1]

        val snapshot = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return
        }

        val canvas = Canvas(snapshot)
        canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())

        val savedBounds = drawable.copyBounds()
        drawable.setBounds(0, 0, source.width, source.height)
        drawable.draw(canvas)
        drawable.bounds = savedBounds

        blurredBitmap = fastBlur(snapshot, BLUR_DOWNSAMPLE, BLUR_RADIUS)
        Log.d(
            "MP3_LiquidGlassDebug",
            "${debugName()}: refreshGlass() OK, foto tomada (${width}x$height, offset=$offsetX,$offsetY)"
        )
        invalidate()
    }

    /**
     * Identificador legible para distinguir en logcat cual instancia de
     * LiquidGlassView esta logueando (hay varias: los accesos rapidos
     * del Home, groupMini, etc). SOLO PARA DEPURACION: quitar junto con
     * los Log.d de este archivo una vez resuelto el bug del mini player.
     */
    private fun debugName(): String {
        return try {
            if (id != NO_ID) resources.getResourceEntryName(id) else "sin-id"
        } catch (e: Exception) {
            "id=$id"
        }
    }


    // ============================================================
    // DIBUJO
    // ============================================================

    /**
     * Cambia el radio de esquina de ESTA instancia (por defecto
     * DEFAULT_CORNER_RADIUS_DP). Llamar antes o despues de que la vista
     * ya tenga tamano: si ya lo tiene, recalcula la forma de inmediato.
     */
    fun setCornerRadiusDp(radiusDp: Float) {
        cornerRadiusPx = radiusDp * density
        if (width > 0 && height > 0) {
            recomputeShape(width, height)
            invalidate()
        }
    }

    private fun recomputeShape(w: Int, h: Int) {
        clipPath.reset()
        clipPath.addRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            cornerRadiusPx,
            cornerRadiusPx,
            Path.Direction.CW
        )

        borderPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.argb(BORDER_ALPHA_TOP, 255, 255, 255),
            Color.argb(BORDER_ALPHA_BOTTOM, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        recomputeShape(w, h)

        if (backdropSource != null) {
            refreshGlass()
        }
    }

    // SOLO PARA DEPURACION: evita loguear en cada onDraw (se llama muy
    // seguido), solo cuando cambia entre "mostrando foto" y "mostrando
    // color de respaldo".
    private var lastDrawUsedFallback: Boolean? = null

    override fun onDraw(canvas: Canvas) {

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        val bitmap = blurredBitmap

        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
            if (lastDrawUsedFallback != false) {
                Log.d("MP3_LiquidGlassDebug", "${debugName()}: onDraw() pintando FOTO (blur)")
                lastDrawUsedFallback = false
            }
        } else {
            // Aun no hay foto del fondo (primer frame): color solido de
            // respaldo para no parpadear en transparente.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallbackPaint)
            if (lastDrawUsedFallback != true) {
                Log.d("MP3_LiquidGlassDebug", "${debugName()}: onDraw() pintando FALLBACK (sin foto)")
                lastDrawUsedFallback = true
            }
        }

        canvas.restoreToCount(saveCount)

        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            cornerRadiusPx - inset,
            cornerRadiusPx - inset,
            borderPaint
        )

        super.onDraw(canvas)
    }


    // ============================================================
    // BLUR POR SOFTWARE
    // ============================================================

    /**
     * Blur rapido por reduccion de escala: se encoge el bitmap (barato),
     * se aplica un box blur de dos pasadas sobre la version chica y se
     * estira de vuelta al tamano original. El propio escalado ya
     * suaviza bastante, asi que el radio necesario sobre la version
     * chica es bajo.
     */
    private fun fastBlur(source: Bitmap, scale: Float, radius: Int): Bitmap {

        val smallWidth = max(1, (source.width * scale).toInt())
        val smallHeight = max(1, (source.height * scale).toInt())

        val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
        boxBlur(small, radius)

        val result = Bitmap.createScaledBitmap(small, source.width, source.height, true)

        small.recycle()
        source.recycle()

        return result
    }

    /**
     * Box blur de dos pasadas (horizontal + vertical) sobre un bitmap,
     * in-place. Aproximacion barata a un gaussian blur, suficiente
     * porque ya trabajamos sobre una version reducida de la imagen.
     */
    private fun boxBlur(bitmap: Bitmap, radius: Int) {

        if (radius < 1) return

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val div = radius * 2 + 1
        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)
        val a = IntArray(w * h)

        // Pasada horizontal: pixels -> r/g/b/a
        for (y in 0 until h) {

            var rSum = 0; var gSum = 0; var bSum = 0; var aSum = 0
            val rowStart = y * w

            for (i in -radius..radius) {
                val px = pixels[rowStart + min(w - 1, max(0, i))]
                aSum += (px shr 24) and 0xFF
                rSum += (px shr 16) and 0xFF
                gSum += (px shr 8) and 0xFF
                bSum += px and 0xFF
            }

            for (x in 0 until w) {

                val idx = rowStart + x
                a[idx] = aSum / div
                r[idx] = rSum / div
                g[idx] = gSum / div
                b[idx] = bSum / div

                val addPx = pixels[rowStart + min(w - 1, x + radius + 1)]
                val subPx = pixels[rowStart + max(0, x - radius)]

                aSum += ((addPx shr 24) and 0xFF) - ((subPx shr 24) and 0xFF)
                rSum += ((addPx shr 16) and 0xFF) - ((subPx shr 16) and 0xFF)
                gSum += ((addPx shr 8) and 0xFF) - ((subPx shr 8) and 0xFF)
                bSum += (addPx and 0xFF) - (subPx and 0xFF)
            }
        }

        // Pasada vertical: r/g/b/a -> pixels (resultado final)
        for (x in 0 until w) {

            var rSum = 0; var gSum = 0; var bSum = 0; var aSum = 0

            for (i in -radius..radius) {
                val y = min(h - 1, max(0, i))
                val idx = y * w + x
                aSum += a[idx]; rSum += r[idx]; gSum += g[idx]; bSum += b[idx]
            }

            for (y in 0 until h) {

                val idx = y * w + x

                pixels[idx] = (aSum / div shl 24) or
                        (rSum / div shl 16) or
                        (gSum / div shl 8) or
                        (bSum / div)

                val addIdx = min(h - 1, y + radius + 1) * w + x
                val subIdx = max(0, y - radius) * w + x

                aSum += a[addIdx] - a[subIdx]
                rSum += r[addIdx] - r[subIdx]
                gSum += g[addIdx] - g[subIdx]
                bSum += b[addIdx] - b[subIdx]
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}