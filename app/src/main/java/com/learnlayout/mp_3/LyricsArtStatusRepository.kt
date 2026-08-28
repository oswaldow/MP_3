
package com.learnlayout.mp_3

import android.content.Context

/**
 * Estado de descarga (letra + caratula) de una sola cancion. [song] ya
 * viene con los overrides de SongMetadataRepository aplicados, para que
 * el titulo/artista mostrado en la lista coincida con lo que el usuario
 * ve en el resto de la app.
 */
data class SongDownloadStatus(
    val song: Song,
    val hasLyrics: Boolean,
    val hasArt: Boolean
) {
    val isComplete: Boolean get() = hasLyrics && hasArt
}

/**
 * Resumen de [items] para pintar los contadores ("Letras: 40 de 52",
 * etc.) tanto en el indicador de Ajustes como en el encabezado de
 * LyricsArtStatusActivity.
 */
data class DownloadStatusSummary(
    val items: List<SongDownloadStatus>
) {
    val total: Int get() = items.size
    val lyricsCount: Int get() = items.count { it.hasLyrics }
    val artCount: Int get() = items.count { it.hasArt }
    val isComplete: Boolean get() = total > 0 && lyricsCount == total && artCount == total
}

/**
 * Calcula, para todas las canciones del dispositivo, si ya tienen letra
 * y/o caratula guardadas localmente. La usan tanto el indicador
 * persistente de SettingsActivity como el listado detallado de
 * LyricsArtStatusActivity, asi que el calculo vive en un solo lugar en
 * vez de duplicarse en las dos pantallas.
 *
 * Como esto vuelve a leer SavedLyricsRepository (SharedPreferences) y
 * hace un File.exists() por cancion, y la descarga masiva de
 * SettingsActivity.downloadAllLyricsAndArt puede ir guardando cosas al
 * mismo tiempo, este calculo SIEMPRE refleja el estado real en disco en
 * el momento en que se llama: no hace falta ningun paso extra para que
 * "se vea" una descarga o actualizacion manual (edicion de letra,
 * resincronizacion, caratula elegida a mano en AlbumArtPickerDialog...),
 * porque todas esas acciones ya escriben en las mismas fuentes
 * (SavedLyricsRepository / AlbumArtRepository) que aqui se leen.
 *
 * Ademas, para cada cancion que TODAVIA no tenga letra y/o caratula
 * guardadas por la app, revisa si el archivo de audio ya las trae
 * embebidas (ver EmbeddedMetadataReader) y, si las encuentra, las cuenta
 * como "completas" y de paso las guarda en las mismas fuentes de siempre
 * (SavedLyricsRepository / AlbumArtRepository), para que el resto de la
 * app (reproductor incluido) las encuentre despues sin volver a leer el
 * archivo. Esto hace que el calculo sea mas lento en bibliotecas grandes
 * (abre cada archivo sin caratula/letra guardada con jaudiotagger), pero
 * dado que ya corre en un hilo de fondo y solo se recalcula al abrir esta
 * pantalla o al "tirar para abajo" (ver LyricsArtStatusActivity), se
 * prefiere la precision sobre la velocidad aqui.
 */
object LyricsArtStatusRepository {

    /**
     * Operacion sincrona (SharedPreferences + File.exists() por cancion,
     * y ademas jaudiotagger para las canciones sin letra/caratula ya
     * guardadas), pero debe llamarse siempre desde un hilo de fondo (ver
     * AppExecutors.runInBackground), nunca desde el hilo principal.
     */
    fun computeStatus(context: Context): DownloadStatusSummary {
        val items = SongRepository.getAllSongs(context).map { rawSong ->
            val song = SongMetadataRepository.apply(context, rawSong)

            val savedLyrics = SavedLyricsRepository.isSaved(context, song.id)
            val savedArt = AlbumArtRepository.hasCachedCoverOnDisk(context, song)

            var hasLyrics = savedLyrics
            var hasArt = savedArt

            if (!savedLyrics || !savedArt) {
                val embedded = EmbeddedMetadataReader.read(context, song)

                if (!savedArt && embedded.artwork != null) {
                    AlbumArtRepository.cacheEmbeddedArtwork(context, song, embedded.artwork)
                    hasArt = true
                }

                if (!savedLyrics && embedded.lyrics != null) {
                    SavedLyricsRepository.save(context, song.id, embedded.lyrics)
                    hasLyrics = true
                }
            }

            SongDownloadStatus(
                song = song,
                hasLyrics = hasLyrics,
                hasArt = hasArt
            )
        }
        return DownloadStatusSummary(items)
    }
}