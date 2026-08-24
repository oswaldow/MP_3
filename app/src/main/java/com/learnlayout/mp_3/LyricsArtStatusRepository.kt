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
 */
object LyricsArtStatusRepository {

    /**
     * Operacion sincrona: rapida (SharedPreferences + File.exists() por
     * cancion), pero debe llamarse siempre desde un hilo de fondo
     * (ver AppExecutors.runInBackground), nunca desde el hilo principal.
     */
    fun computeStatus(context: Context): DownloadStatusSummary {
        val items = SongRepository.getAllSongs(context).map { rawSong ->
            val song = SongMetadataRepository.apply(context, rawSong)
            SongDownloadStatus(
                song = song,
                hasLyrics = SavedLyricsRepository.isSaved(context, song.id),
                hasArt = AlbumArtRepository.hasCachedCoverOnDisk(context, song)
            )
        }
        return DownloadStatusSummary(items)
    }
}