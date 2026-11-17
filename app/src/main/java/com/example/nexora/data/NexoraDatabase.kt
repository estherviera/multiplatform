package com.example.nexora.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Usuario::class, Video::class], version = 1, exportSchema = false)
abstract class NexoraDatabase : RoomDatabase() {

    abstract fun usuarioDao(): UsuarioDao
    abstract fun videoDao(): VideoDao

    companion object {
        // Patrón Singleton: solo existe una instancia de la base de datos en toda la app
        @Volatile
        private var instancia: NexoraDatabase? = null

        fun obtenerBaseDeDatos(context: Context): NexoraDatabase {
            return instancia ?: synchronized(this) {
                val nueva = Room.databaseBuilder(
                    context.applicationContext,
                    NexoraDatabase::class.java,
                    "nexora_database"
                ).build()
                instancia = nueva
                nueva
            }
        }
    }
}
