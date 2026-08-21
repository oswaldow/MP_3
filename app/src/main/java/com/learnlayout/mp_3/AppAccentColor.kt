package com.learnlayout.mp_3

/**
 * Fuente unica de verdad para el "color de acento" dinamico de la app
 * (el color extraido de la caratula de la cancion en reproduccion, via
 * PlayerPaletteTheme).
 *
 * Antes cada pantalla decidia su propio color: el panel del reproductor
 * (PlayerPanelController) SI seguia el color de la caratula, pero el tab
 * activo, los botones de Home y los chips del ecualizador se quedaban
 * fijos en R.color.purple_primary / R.color.spotify_green. Esto centraliza
 * ese valor para que todos consuman lo mismo.
 *
 * - Cuando hay una cancion sonando (o pausada) con caratula, [update] se
 *   llama con el color extraido por PlayerPaletteTheme.
 * - Cuando no hay caratula (placeholder) o no hay cancion activa, se llama
 *   [reset] y [current] vuelve a ser null: en ese caso cada consumidor debe
 *   usar su propio fallback fijo (R.color.purple_primary), que es
 *   explicitamente el "morado por defecto", no un valor mas del tema.
 *
 * No depende de Context ni de ciclo de vida de Activity: es un simple
 * publisher en memoria. Cada consumidor se suscribe con [addListener] en su
 * setup/onCreate/onResume y se desuscribe con [removeListener] en su
 * onDestroy/onPause para no quedar con una referencia viva.
 */
object AppAccentColor {

    /** Color actual extraido de la caratula, o null si no aplica (usar fallback morado). */
    var current: Int? = null
        private set

    private val listeners = mutableListOf<(Int?) -> Unit>()

    /** Llamado cuando PlayerPaletteTheme calcula un color nuevo a partir de la caratula. */
    fun update(color: Int) {
        current = color
        listeners.forEach { it(color) }
    }

    /** Llamado cuando no hay caratula/cancion: los consumidores deben usar su fallback morado. */
    fun reset() {
        current = null
        listeners.forEach { it(null) }
    }

    /**
     * Se suscribe a los cambios de color. Se invoca inmediatamente con el
     * valor actual para que el consumidor no tenga que esperar la primera
     * cancion para pintar algo coherente.
     */
    fun addListener(listener: (Int?) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (Int?) -> Unit) {
        listeners.remove(listener)
    }
}