package com.learnlayout.mp_3

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlaylistEntity::class, PlaylistSongCrossRef::class, PlayCountEntity::class, SongGainEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun playCountDao(): PlayCountDao
    abstract fun songGainDao(): SongGainDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // Agrega la tabla song_gains (normalizacion de volumen) sin tocar
        // las tablas existentes ni borrar playlists/historial del usuario.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `song_gains` (" +
                            "`songId` INTEGER NOT NULL, " +
                            "`gainDb` REAL NOT NULL, " +
                            "PRIMARY KEY(`songId`))"
                )
            }
        }

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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}