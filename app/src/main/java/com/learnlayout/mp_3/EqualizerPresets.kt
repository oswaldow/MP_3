package com.learnlayout.mp_3

/**
 * Preset de ecualizador. El orden de gainsDb coincide con:
 * 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k y 16k Hz.
 */
data class EqPreset(val label: String, val gainsDb: IntArray)

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
}