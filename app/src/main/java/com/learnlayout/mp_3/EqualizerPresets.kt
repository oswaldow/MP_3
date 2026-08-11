package com.learnlayout.mp_3

/**
 * Un preset de ecualizador: nombre visible + ganancia por banda en dB.
 * El orden de gainsDb debe coincidir con
 * SoftwareEqualizerProcessor.CENTER_FREQS_HZ (60, 230, 910, 3600, 14000 Hz).
 */
data class EqPreset(val label: String, val gainsDb: IntArray)

object EqualizerPresets {

    val ALL = listOf(
        EqPreset("Plano", intArrayOf(0, 0, 0, 0, 0)),
        EqPreset("Pop", intArrayOf(-1, 2, 3, 3, -1)),
        EqPreset("Rock", intArrayOf(5, 3, -3, 2, 4)),
        EqPreset("Jazz", intArrayOf(3, 2, 0, 2, 3)),
        EqPreset("Clásica", intArrayOf(3, 2, 0, 2, 5)),
        EqPreset("Electrónica", intArrayOf(6, 2, -2, 1, 5)),
        EqPreset("Hip-Hop", intArrayOf(7, 4, -1, 2, 2)),
        EqPreset("Voz", intArrayOf(-4, -1, 5, 5, -2)),
        EqPreset("Graves+", intArrayOf(9, 5, 0, -1, -2)),
        EqPreset("Agudos+", intArrayOf(-2, -1, 0, 4, 8))
    )
}