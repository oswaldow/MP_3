package com.learnlayout.mp_3

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PlaylistEntity::class, PlaylistSongCrossRef::class, PlayCountEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun playCountDao(): PlayCountDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mp3_app.db"
                )
                    // El resto de la app todavia llama a los repositorios de forma
                    // sincrona desde el hilo principal (sin corrutinas). Para no
                    // tener que reescribir todos esos call sites en este mismo
                    // cambio, se permiten consultas en el hilo principal aqui.
                    // TODO: cuando se migre PlaylistRepository/PlayCountRepository
                    // a funciones suspend + corrutinas, quitar esta linea.
                    .allowMainThreadQueries()
                    .build()
                    .also { instance = it }
            }
        }
    }
}