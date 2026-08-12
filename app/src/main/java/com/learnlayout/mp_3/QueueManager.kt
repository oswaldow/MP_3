package com.learnlayout.mp_3

/**
 * Dueño de la lista de reproducción.
 *
 * Mantiene:
 * - la lista original
 * - la lista actualmente utilizada
 * - el índice de la canción actual
 * - el modo de reproducción
 *
 * No conoce ExoPlayer, notificaciones ni MediaSession.
 */
class QueueManager {

    private var originalList: List<Song> = emptyList()

    private var songList: List<Song> = emptyList()

    private var currentIndex: Int = 0

    private var playbackMode: MusicService.PlaybackMode =
        MusicService.PlaybackMode.NORMAL


    fun getCurrentSong(): Song? =
        songList.getOrNull(currentIndex)


    fun getSongList(): List<Song> =
        songList


    /** Lista base que se conserva para reconstruir correctamente el modo shuffle. */
    fun getOriginalSongList(): List<Song> =
        originalList


    fun getCurrentIndex(): Int =
        currentIndex


    fun setCurrentIndex(index: Int) {

        if (index in songList.indices) {
            currentIndex = index
        }
    }


    fun getPlaybackMode(): MusicService.PlaybackMode =
        playbackMode


    fun songAt(index: Int): Song? =
        songList.getOrNull(index)


    fun isEmpty(): Boolean =
        songList.isEmpty()


    fun size(): Int =
        songList.size


    fun nextIndexFrom(index: Int): Int {

        if (songList.isEmpty()) {
            return -1
        }

        return if (index + 1 >= songList.size) {
            0
        } else {
            index + 1
        }
    }


    fun previousIndexFrom(index: Int): Int {

        if (songList.isEmpty()) {
            return -1
        }

        return if (index - 1 < 0) {
            songList.size - 1
        } else {
            index - 1
        }
    }


    fun setPlaylist(
        songs: List<Song>,
        startIndex: Int
    ) {

        originalList = songs.toList()

        songList = songs.toList()

        currentIndex =
            startIndex.coerceIn(
                0,
                (songs.size - 1).coerceAtLeast(0)
            )

        playbackMode =
            MusicService.PlaybackMode.NORMAL
    }


    /**
     * Restaura una cola persistida conservando EXACTAMENTE el orden que
     * tenia el usuario, la lista original usada por shuffle, el indice
     * actual y el modo de reproduccion.
     */
    fun restorePersistedQueue(
        songs: List<Song>,
        persistedOriginalList: List<Song>,
        startIndex: Int,
        mode: MusicService.PlaybackMode
    ) {
        if (songs.isEmpty()) {
            originalList = emptyList()
            songList = emptyList()
            currentIndex = 0
            playbackMode = MusicService.PlaybackMode.NORMAL
            return
        }

        songList = songs.toList()
        originalList = if (persistedOriginalList.isNotEmpty()) {
            persistedOriginalList.toList()
        } else {
            songs.toList()
        }
        currentIndex = startIndex.coerceIn(0, songs.lastIndex)
        playbackMode = mode
    }

    fun restorePlaylist(
        songs: List<Song>,
        startIndex: Int
    ) {

        if (songs.isEmpty()) {

            originalList = emptyList()

            songList = emptyList()

            currentIndex = 0

            playbackMode =
                MusicService.PlaybackMode.NORMAL

            return
        }

        originalList = songs.toList()

        songList = songs.toList()

        currentIndex =
            startIndex.coerceIn(
                0,
                songs.lastIndex
            )

        playbackMode =
            MusicService.PlaybackMode.NORMAL
    }


    fun cyclePlaybackMode(): MusicService.PlaybackMode {

        val next = when (playbackMode) {

            MusicService.PlaybackMode.NORMAL ->
                MusicService.PlaybackMode.REPEAT_ONE

            MusicService.PlaybackMode.REPEAT_ONE ->
                MusicService.PlaybackMode.SHUFFLE

            MusicService.PlaybackMode.SHUFFLE ->
                MusicService.PlaybackMode.NORMAL
        }

        setPlaybackMode(next)

        return next
    }


    fun setPlaybackMode(
        mode: MusicService.PlaybackMode
    ) {

        val currentSong =
            getCurrentSong()

        playbackMode = mode

        if (originalList.isEmpty()) {
            return
        }

        when (mode) {

            MusicService.PlaybackMode.SHUFFLE -> {

                val rest =
                    originalList
                        .filter {
                            it.id != currentSong?.id
                        }
                        .shuffled()

                val newList =
                    mutableListOf<Song>()

                if (currentSong != null) {
                    newList.add(currentSong)
                }

                newList.addAll(rest)

                songList = newList

                currentIndex = 0
            }


            else -> {

                songList = originalList

                val foundIndex =
                    originalList.indexOfFirst {
                        it.id == currentSong?.id
                    }

                currentIndex =
                    if (foundIndex >= 0) {
                        foundIndex
                    } else {
                        0
                    }
            }
        }
    }


    /**
     * Mueve una canción dentro de la cola.
     */
    fun moveQueueItem(
        fromIndex: Int,
        toIndex: Int
    ): Boolean {

        if (fromIndex == toIndex) {
            return false
        }

        if (
            fromIndex !in songList.indices ||
            toIndex !in songList.indices
        ) {
            return false
        }

        val mutableList =
            songList.toMutableList()

        val movingSong =
            mutableList.removeAt(fromIndex)

        mutableList.add(
            toIndex,
            movingSong
        )

        songList = mutableList

        currentIndex = when {

            fromIndex == currentIndex ->
                toIndex

            fromIndex < currentIndex &&
                    toIndex >= currentIndex ->
                currentIndex - 1

            fromIndex > currentIndex &&
                    toIndex <= currentIndex ->
                currentIndex + 1

            else ->
                currentIndex
        }

        if (
            playbackMode !=
            MusicService.PlaybackMode.SHUFFLE
        ) {
            originalList = songList
        }

        return true
    }


    /**
     * Elimina una canción de la cola.
     *
     * La canción que está sonando no se puede eliminar.
     */
    fun removeQueueItem(
        index: Int
    ): Boolean {

        if (index !in songList.indices) {
            return false
        }

        if (index == currentIndex) {
            return false
        }

        if (songList.size <= 1) {
            return false
        }

        val songToRemove =
            songList[index]

        val newQueue =
            songList.toMutableList()

        newQueue.removeAt(index)

        songList = newQueue

        if (index < currentIndex) {
            currentIndex--
        }

        originalList =
            originalList.filter {
                it.id != songToRemove.id
            }

        if (
            originalList.isEmpty() &&
            songList.isNotEmpty()
        ) {
            originalList = songList
        }

        return true
    }


    /**
     * Elimina todas las canciones posteriores
     * a la canción actual.
     */
    fun clearUpcomingQueue(): Int {

        if (songList.isEmpty()) {
            return 0
        }

        if (currentIndex !in songList.indices) {
            return 0
        }

        if (currentIndex >= songList.lastIndex) {
            return 0
        }

        val songsToKeep =
            songList
                .subList(
                    0,
                    currentIndex + 1
                )
                .toList()

        val removedCount =
            songList.size -
                    songsToKeep.size

        val removedSongs =
            songList.drop(
                currentIndex + 1
            )

        songList = songsToKeep

        val removedIds =
            removedSongs
                .map {
                    it.id
                }
                .toSet()

        originalList =
            originalList.filter {
                it.id !in removedIds
            }

        if (
            originalList.isEmpty() &&
            songList.isNotEmpty()
        ) {
            originalList = songList
        }

        return removedCount
    }


    /**
     * Inserta una canción inmediatamente
     * después de la canción actual.
     */
    fun addToPlayNext(
        song: Song
    ): Boolean {

        if (songList.isEmpty()) {
            return false
        }

        val insertAt =
            currentIndex + 1

        val mutableList =
            songList.toMutableList()

        mutableList.add(
            insertAt,
            song
        )

        songList = mutableList

        if (
            playbackMode !=
            MusicService.PlaybackMode.SHUFFLE
        ) {
            originalList = songList
        }

        return true
    }
}