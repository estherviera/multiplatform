package com.example.nexora.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// DAO: aquí están las consultas a la tabla "videos"
@Dao
interface VideoDao {

    // Devuelve un Flow: cada vez que cambia la tabla, la lista se actualiza sola en la pantalla
    @Query("SELECT * FROM videos ORDER BY fecha DESC")
    fun obtenerTodos(): Flow<List<Video>>

    @Insert
    suspend fun insertar(video: Video)

    @Delete
    suspend fun borrar(video: Video)
}
