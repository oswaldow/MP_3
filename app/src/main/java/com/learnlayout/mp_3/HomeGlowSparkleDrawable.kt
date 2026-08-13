package com.learnlayout.mp_3

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import kotlin.random.Random

/**
 * Drawable animado que dibuja "destellos" (chispas de luz) sobre el Home,
 * parecido al efecto vivo/luminoso del reproductor del sistema, pero en
 * movimiento: los destellos van apareciendo, brillan un instante y se
 * apagan en distintos puntos de la pantalla, como pequeños fuegos
 * artificiales, en bucle mientras haya una cancion activa/reciente.
 *
 * Se usa como una capa MAS dentro del LayerDrawable del fondo del Home
 * (ver HomeController), encima del degradado oscuro de base.
 *
 * No depende de ValueAnimator: se auto-programa usando
 * [scheduleSelf]/[unscheduleSelf], que es el mecanismo estandar de
 * Android para Drawables que se animan solos.
 */
class HomeGlowSparkleDrawable(
    private val density: Float
) : Drawable() {

    // ============================================================
    // CHISPA INDIVIDUAL
    // ============================================================

    private class Spark(
        val cxPx: Float,        // posicion horizontal, en pixeles absolutos
        val cyPx: Float,        // posicion vertical, en pixeles absolutos
        val radiusPx: Float,
        val startTime: Long,
        val durationMs: Long,
        val shader: RadialGradient
    )


    // ============================================================
    // CONFIGURACION DEL EFECTO
    // ============================================================

    private companion object {

        // Fraccion de la duracion de una chispa en la que "enciende"
        // (crece rapido de 0 a brillo maximo). El resto es el apagado.
        // Un attack corto + decay largo es lo que da la sensacion de
        // "destello"/chispazo en vez de un simple parpadeo parejo.
        const val ATTACK_FRACTION = 0.14f

        const val MIN_RADIUS_DP = 60f
        const val MAX_RADIUS_DP = 150f

        const val MIN_DURATION_MS = 900L
        const val DURATION_JITTER_MS = 900L

        const val MIN_SPAWN_GAP_MS = 220L
        const val MAX_SPAWN_GAP_MS = 600L

        const val MAX_CONCURRENT_SPARKS = 6

        // Region donde pueden nacer las chispas (fraccion del alto del
        // fondo). Se concentran arriba, donde esta la tarjeta principal,
        // igual que el resplandor del reproductor del sistema.
        const val SPAWN_MIN_Y = 0.0f
        const val SPAWN_MAX_Y = 0.55f

        const val SPAWN_MIN_X = 0.05f
        const val SPAWN_MAX_X = 0.95f

        const val FRAME_DELAY_MS = 16L // ~60fps
    }


    // ============================================================
    // ESTADO
    // ============================================================

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val sparks =
        mutableListOf<Spark>()

    private val random =
        Random(System.nanoTime())

    /** Color base (ya extraido de la caratula) sobre el que se generan las chispas. */
    private var accentColor: Int =
        Color.WHITE

    /** Mientras sea false no se generan chispas nuevas (pero las que ya estan vivas terminan de apagarse). */
    private var active = false

    private var nextSpawnAt = 0L

    private var scheduled = false

    private val tickRunnable =
        Runnable { invalidateSelf() }


    // ============================================================
    // API PUBLICA
    // ============================================================

    /**
     * Actualiza el color base de las chispas (color extraido de la
     * caratula con Palette). Las chispas ya visibles conservan su
     * color y terminan de apagarse solas; las nuevas usan el color
     * actualizado, asi que la transicion entre canciones se ve
     * natural, sin cortes.
     */
    fun setAccentColor(rawColor: Int) {
        accentColor = rawColor
    }

    /**
     * Activa o desactiva la generacion de chispas nuevas.
     * Cuando no hay ninguna cancion (biblioteca vacia), se llama
     * con false: las chispas existentes se apagan solas y despues
     * el fondo queda quieto (sin gastar animaciones de mas).
     */
    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        if (active) {
            nextSpawnAt = SystemClock.uptimeMillis()
            invalidateSelf()
        }
    }


    // ============================================================
    // DIBUJO
    // ============================================================

    override fun draw(canvas: Canvas) {

        val now =
            SystemClock.uptimeMillis()

        if (active) {
            maybeSpawnSpark(now)
        }

        if (sparks.isNotEmpty()) {

            val iterator =
                sparks.iterator()

            while (iterator.hasNext()) {

                val spark =
                    iterator.next()

                val t =
                    (now - spark.startTime)
                        .toFloat() / spark.durationMs

                if (t >= 1f) {
                    iterator.remove()
                    continue
                }

                val alphaFactor =
                    envelope(t)

                val alpha =
                    (alphaFactor * 255f)
                        .toInt()
                        .coerceIn(0, 255)

                if (alpha <= 0) {
                    continue
                }

                paint.shader = spark.shader
                paint.alpha = alpha

                canvas.drawCircle(
                    spark.cxPx,
                    spark.cyPx,
                    spark.radiusPx,
                    paint
                )
            }
        }

        // --------------------------------------------------------
        // SIGUIENTE CUADRO
        // --------------------------------------------------------

        val stillNeedsFrames =
            active || sparks.isNotEmpty()

        if (stillNeedsFrames) {

            if (!scheduled) {
                scheduled = true
            }

            scheduleSelf(
                tickRunnable,
                now + FRAME_DELAY_MS
            )

        } else {
            scheduled = false
        }
    }


    /**
     * Curva de brillo de una chispa: sube rapido (destello) y baja
     * mas lento (se apaga), en vez de un fade parejo. Eso es lo que
     * da la sensacion de chispazo/fuego artificial en vez de un
     * simple pulso de opacidad.
     */
    private fun envelope(t: Float): Float {

        return if (t < ATTACK_FRACTION) {
            t / ATTACK_FRACTION
        } else {
            1f - (t - ATTACK_FRACTION) / (1f - ATTACK_FRACTION)
        }.coerceIn(0f, 1f)
    }


    // ============================================================
    // GENERACION DE CHISPAS
    // ============================================================

    private fun maybeSpawnSpark(now: Long) {

        if (now < nextSpawnAt) {
            return
        }

        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return
        }

        if (sparks.size >= MAX_CONCURRENT_SPARKS) {
            nextSpawnAt = now + MIN_SPAWN_GAP_MS
            return
        }

        // A veces nacen 2 chispas casi juntas, para que se vea mas
        // como una rafaga de destellos que como un metronomo.
        val count =
            if (random.nextFloat() < 0.3f) 2 else 1

        repeat(count) {
            if (sparks.size < MAX_CONCURRENT_SPARKS) {
                spawnSpark(now)
            }
        }

        nextSpawnAt =
            now + random.nextLong(
                MIN_SPAWN_GAP_MS,
                MAX_SPAWN_GAP_MS
            )
    }

    private fun spawnSpark(now: Long) {

        val fractionX =
            SPAWN_MIN_X + random.nextFloat() * (SPAWN_MAX_X - SPAWN_MIN_X)

        val fractionY =
            SPAWN_MIN_Y + random.nextFloat() * (SPAWN_MAX_Y - SPAWN_MIN_Y)

        // Coordenadas ABSOLUTAS en pixeles, ya resueltas contra los
        // bounds actuales del drawable (el tamaño real de la pantalla).
        // Esto es lo que estaba mal antes: el shader se construia
        // centrado en (0,0) sin importar donde se dibujaba el circulo
        // despues, asi que solo se veia color cerca de la esquina
        // superior izquierda. Ahora el centro del gradiente y el
        // centro del circulo dibujado son EXACTAMENTE el mismo punto.
        val cxPx =
            bounds.left + fractionX * bounds.width()

        val cyPx =
            bounds.top + fractionY * bounds.height()

        val radiusDp =
            MIN_RADIUS_DP + random.nextFloat() * (MAX_RADIUS_DP - MIN_RADIUS_DP)

        val radiusPx =
            (radiusDp * density).coerceAtLeast(1f)

        val duration =
            MIN_DURATION_MS + random.nextLong(0, DURATION_JITTER_MS)

        val color =
            jitteredVividColor(accentColor)

        // El shader se crea UNA sola vez al nacer la chispa (no en
        // cada cuadro): el brillo se anima despues solo cambiando
        // paint.alpha, que es mucho mas barato que reconstruir el
        // degradado 60 veces por segundo. Por eso es clave que ya
        // nazca centrado en su posicion final (cxPx, cyPx).
        val shader =
            RadialGradient(
                cxPx,
                cyPx,
                radiusPx,
                color,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )

        sparks.add(
            Spark(
                cxPx = cxPx,
                cyPx = cyPx,
                radiusPx = radiusPx,
                startTime = now,
                durationMs = duration,
                shader = shader
            )
        )
    }

    /**
     * Version viva/luminosa del color de acento, con una pequena
     * variacion aleatoria de brillo para que no todas las chispas
     * se vean identicas (como chispas reales de distinto tamano
     * e intensidad).
     */
    private fun jitteredVividColor(color: Int): Int {

        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)

        hsl[1] = hsl[1].coerceIn(0.55f, 1f)

        val jitter =
            (random.nextFloat() * 0.18f) - 0.09f

        hsl[2] =
            (hsl[2].coerceIn(0.30f, 0.55f) + jitter)
                .coerceIn(0.38f, 0.66f)

        // Color con alpha completo: la transparencia real la aplica
        // paint.alpha en cada cuadro segun el "envelope".
        return ColorUtils.setAlphaComponent(
            ColorUtils.HSLToColor(hsl),
            255
        )
    }


    // ============================================================
    // CICLO DE VIDA DEL DRAWABLE
    // ============================================================

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        // Las chispas activas guardan coordenadas absolutas en pixeles,
        // calculadas contra el tamaño anterior. Si el tamaño cambia
        // (por ejemplo al rotar la pantalla), las descartamos en vez
        // de dejarlas mal ubicadas; las nuevas chispas usaran el
        // tamaño correcto.
        sparks.clear()
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {

        val changed =
            super.setVisible(visible, restart)

        if (!visible) {
            unscheduleSelf(tickRunnable)
            scheduled = false
        } else {
            invalidateSelf()
        }

        return changed
    }


    // ============================================================
    // OVERRIDES OBLIGATORIOS DE Drawable
    // ============================================================

    override fun setAlpha(alpha: Int) {
        // El alpha global no se usa: cada chispa maneja su propio
        // alpha segun su "envelope" de encendido/apagado.
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}