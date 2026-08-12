package com.learnlayout.mp_3

/**
 * Dueño de la lista de reproducción: el orden "base" (originalList), el
 * orden que se está usando ahora mismo (songList, que en modo SHUFFLE es
 * distinto de originalList), el índice actual y el modo de reproducción.
 *
 * No sabe nada de ExoPlayer, notificaciones ni MediaSession: solo
 * administra listas en memoria. Extraído de MusicService para que ese
 * archivo no siga creciendo cada vez que se toca algo de la cola.
 */
class QueueManager {

    private var originalList: List<Song> = emptyList()
    private var songList: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var playbackMode: MusicService.PlaybackMode = MusicService.PlaybackMode.NORMAL

    fun getCurrentSong(): Song? = songList.getOrNull(currentIndex)

    fun getSongList(): List<Song> = songList

    fun getCurrentIndex(): Int = currentIndex

    fun setCurrentIndex(index: Int) {
        if (index in songList.indices) currentIndex = index
    }

    fun getPlaybackMode(): MusicService.PlaybackMode = playbackMode

    fun songAt(index: Int): Song? = songList.getOrNull(index)

    fun isEmpty(): Boolean = songList.isEmpty()

    fun size(): Int = songList.size

    fun nextIndexFrom(index: Int): Int {
        if (songList.isEmpty()) return -1
        return if (index + 1 >= songList.size) 0 else index + 1
    }

    fun previousIndexFrom(index: Int): Int {
        if (songList.isEmpty()) return -1
        return if (index - 1 < 0) songList.size - 1 else index - 1
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int) {
        originalList = songs
        songList = songs
        currentIndex = startIndex
        playbackMode = MusicService.PlaybackMode.NORMAL
    }

    /** Igual que setPlaylist pero pensado para restaurar sin contarlo como reproducción nueva. */
    fun restorePlaylist(songs: List<Song>, startIndex: Int) {
        originalList = songs
        songList = songs
        currentIndex = startIndex.coerceIn(0, songs.size - 1)
        playbackMode = MusicService.PlaybackMode.NORMAL
    }

    fun cyclePlaybackMode(): MusicService.PlaybackMode {
        val next = when (playbackMode) {
            MusicService.PlaybackMode.NORMAL -> MusicService.PlaybackMode.REPEAT_ONE
            MusicService.PlaybackMode.REPEAT_ONE -> MusicService.PlaybackMode.SHUFFLE
            MusicService.PlaybackMode.SHUFFLE -> MusicService.PlaybackMode.NORMAL
        }
        setPlaybackMode(next)
        return next
    }

    fun setPlaybackMode(mode: MusicService.PlaybackMode) {
        val currentSong = getCurrentSong()
        playbackMode = mode

        if (originalList.isEmpty()) return

        when (mode) {
            MusicService.PlaybackMode.SHUFFLE -> {
                val rest = originalList.filter { it.id != currentSong?.id }.shuffled()
                val newList = mutableListOf<Song>()
                if (currentSong != null) newList.add(currentSong)
                newList.addAll(rest)
                songList = newList
                currentIndex = 0
            }
            else -> {
                songList = originalList
                val foundIndex = originalList.indexOfFirst { it.id == currentSong?.id }
                currentIndex = if (foundIndex >= 0) foundIndex else 0
            }
        }
    }

    /** @return true si sí se pudo mover (para que MusicService sepa si debe cancelar el crossfade). */
    fun moveQueueItem(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex == toIndex) return false
        if (fromIndex !in songList.indices || toIndex !in songList.indices) return false

        val mutableList = songList.toMutableList()
        val movingSong = mutableList.removeAt(fromIndex)
        mutableList.add(toIndex, movingSong)
        songList = mutableList

        currentIndex = when {
            fromIndex == currentIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }

        if (playbackMode != MusicService.PlaybackMode.SHUFFLE) {
            originalList = songList
        }
        return true
    }

    /**
     * Elimina una canción de la cola sin detener la reproducción actual.
     * La canción que está sonando no se puede eliminar. Se mantiene también
     * eliminada de originalList (importante en SHUFFLE, para que no vuelva
     * a aparecer al desactivar el modo aleatorio).
     */
    fun removeQueueItem(index: Int): Boolean {
        if (index !in songList.indices) return false
        if (index == currentIndex) return false
        if (songList.size <= 1) return false

        val songToRemove = songList[index]

        val newQueue = songList.toMutableList()
        newQueue.removeAt(index)
        songList = newQueue

        if (index < currentIndex) {
            currentIndex--
        }

        originalList = originalList.filter { it.id != songToRemove.id }

        if (originalList.isEmpty() && songList.isNotEmpty()) {
            originalList = songList
        }

        return true
    }

    /**
     * Elimina todas las canciones que vienen después de la canción actual.
     * La canción actual permanece intacta y la reproducción no se detiene.
     * @return cantidad de canciones eliminadas.
     */
    fun clearUpcomingQueue(): Int {
        if (songList.isEmpty()) return 0
        if (currentIndex !in songList.indices) return 0
        if (currentIndex >= songList.lastIndex) return 0

        val songsToKeep = songList.subList(0, currentIndex + 1).toList()
        val removedCount = songList.size - songsToKeep.size
        val removedSongs = songList.drop(currentIndex + 1)

        songList = songsToKeep

        val removedIds = removedSongs.map { it.id }.toSet()
        originalList = originalList.filter { it.id !in removedIds }

        if (originalList.isEmpty() && songList.isNotEmpty()) {
            originalList = songList
        }

        return removedCount
    }

    /**
     * Inserta una canción justo después de la actual, para que suene a
     * continuación (como "Agregar a la cola" en Spotify).
     * @return true si la cola ya tenía canciones (false si esta llamada
     * dejó la cola en un estado de "arrancar desde cero" que MusicService
     * debe manejar con setPlaylist).
     */
    fun addToPlayNext(song: Song): Boolean {
        if (songList.isEmpty()) return false

        val insertAt = currentIndex + 1
        val mutableList = songList.toMutableList()
        mutableList.add(insertAt, song)
        songList = mutableList

        if (playbackMode != MusicService.PlaybackMode.SHUFFLE) {
            originalList = songList
        }
        return true
    }
}