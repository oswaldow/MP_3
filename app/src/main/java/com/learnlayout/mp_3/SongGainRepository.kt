package com.learnlayout.mp_3

/**
 * Calcula y cachea, por cancion, cuanta ganancia (en dB) hay que aplicarle
 * para que suene a un volumen promedio parecido al resto de la biblioteca
 * (ver LoudnessAnalyzer para como se mide, y ReplayGainAudioProcessor para
 * donde se aplica sobre el audio).
 */
object SongGainRepository {

    // Nivel de referencia al que intentamos "llevar" cada cancion. No es un
    // estandar de loudness real (ver aviso en LoudnessAnalyzer), solo un
    // punto medio razonable para la mayoria de la musica popular.
    private const val TARGET_RMS_DB = -18.0

    // Limite de cuanto se puede subir o bajar una cancion: evita que una
    // pista casi en silencio (o con ruido raro) se dispare a una ganancia
    // absurda que suene peor que sin normalizar.
    private const val MAX_GAIN_DB = 12.0
    private const val MIN_GAIN_DB = -12.0

    // Canciones que ya se estan analizando en este momento, para no lanzar
    // el mismo analisis (lento) dos veces si el usuario salta rapido entre
    // canciones.
    private val analyzing = mutableSetOf<Long>()

    private fun dao(context: android.content.Context) = AppDatabase.getInstance(context).songGainDao()

    /** Ganancia ya calculada y guardada para [songId], o null si aun no se analizo. */
    fun getCachedGainDb(context: android.content.Context, songId: Long): Double? {
        return dao(context).getGain(songId)?.gainDb
    }

    /**
     * Aplica de inmediato la ganancia que ya se conozca de [song] (o 0 dB
     * si todavia no se ha analizado) y, si hace falta, lanza el analisis
     * en segundo plano. Si el analisis termina mientras esta misma
     * cancion sigue sonando, [onGainReady] permite refrescar la ganancia
     * en vivo en vez de esperar a la siguiente reproduccion.
     */
    fun applyGainForSong(context: android.content.Context, song: Song, onGainReady: ((Double) -> Unit)? = null) {
        val cached = getCachedGainDb(context, song.id)
        if (cached != null) {
            ReplayGainAudioProcessor.setCurrentGainDb(cached)
            return
        }

        // Sin dato todavia: se reproduce sin normalizar mientras se
        // analiza, en vez de bloquear el arranque de la cancion.
        ReplayGainAudioProcessor.setCurrentGainDb(0.0)

        synchronized(analyzing) {
            if (!analyzing.add(song.id)) return
        }

        AppExecutors.runInBackground {
            val rmsDb = LoudnessAnalyzer.analyzeRmsDb(context, song.uri)
            val gainDb = if (rmsDb != null) {
                (TARGET_RMS_DB - rmsDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            } else {
                0.0
            }

            dao(context).insertGain(SongGainEntity(song.id, gainDb))

            synchronized(analyzing) { analyzing.remove(song.id) }

            AppExecutors.runOnMain {
                onGainReady?.invoke(gainDb)
            }
        }
    }
}