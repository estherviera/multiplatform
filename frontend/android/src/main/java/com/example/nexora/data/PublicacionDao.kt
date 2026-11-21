package com.example.nexora.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PublicacionDao {
    @Query("SELECT * FROM publicaciones WHERE empresaId = :empresaId ORDER BY fecha DESC")
    fun porEmpresa(empresaId: String): Flow<List<Publicacion>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(publicaciones: List<Publicacion>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarUna(publicacion: Publicacion)
}
