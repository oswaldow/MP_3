package com.learnlayout.mp_3

import kotlin.math.ln

/**
 * Preset de ecualizador, definido sobre 10 puntos de referencia fijos:
 * 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k y 16k Hz. Estos puntos NO son las
 * bandas reales del dispositivo (el Equalizer nativo de Android trae las
 * que decida el fabricante, normalmente 5-6, ver EqualizerRepository) -
 * son solo la "forma" del preset. EqualizerActivity.applyPreset()
 * interpola esta forma contra las frecuencias reales de cada banda del
 * equipo via gainAtHz(), asi el mismo preset se ve razonable sin importar
 * cuantas bandas tenga el hardware.
 */
data class EqPreset(val label: String, val gainsDb: IntArray) {
    companion object {
        val REFERENCE_FREQS_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}

object EqualizerPresets {
    val ALL = listOf(
        EqPreset("Plano",       intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        EqPreset("Pop",         intArrayOf(-1, 1, 2, 1, -1, 1, 2, 3, 2, -1)),
        EqPreset("Rock",        intArrayOf(4, 5, 3, 1, -2, -1, 2, 4, 5, 4)),
        EqPreset("Jazz",        intArrayOf(3, 4, 2, 0, -1, 1, 2, 3, 4, 3)),
        EqPreset("Clásica",     intArrayOf(4, 3, 2, 0, -1, 0, 2, 3, 4, 5)),
        EqPreset("Electrónica", intArrayOf(5, 6, 3, 1, -2, 1, 3, 5, 6, 4)),
        EqPreset("Hip-Hop",     intArrayOf(6, 7, 5, 2, -1, -1, 2, 3, 3, 2)),
        EqPreset("Voz",         intArrayOf(-4, -2, 0, 2, 4, 5, 5, 3, 0, -2)),
        EqPreset("Graves+",     intArrayOf(7, 8, 6, 4, 1, 0, -1, -2, -2, -2)),
        EqPreset("Agudos+",     intArrayOf(-2, -1, -1, 0, 0, 1, 3, 5, 7, 7))
    )

    /**
     * Ganancia en dB que le corresponde a [hz] dentro de [preset],
     * interpolando linealmente en escala logaritmica de frecuencia entre
     * los dos puntos de referencia mas cercanos (o el valor del extremo
     * si [hz] cae fuera del rango 31-16000).
     */
    fun gainAtHz(preset: EqPreset, hz: Int): Float {
        val freqs = EqPreset.REFERENCE_FREQS_HZ
        val gains = preset.gainsDb
        if (hz <= freqs.first()) return gains.first().toFloat()
        if (hz >= freqs.last()) return gains.last().toFloat()

        for (i in 0 until freqs.size - 1) {
            val lowHz = freqs[i]
            val highHz = freqs[i + 1]
            if (hz in lowHz..highHz) {
                val logLow = ln(lowHz.toFloat())
                val logHigh = ln(highHz.toFloat())
                val logHz = ln(hz.toFloat())
                val t = if (logHigh > logLow) (logHz - logLow) / (logHigh - logLow) else 0f
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return 0f
    }
}