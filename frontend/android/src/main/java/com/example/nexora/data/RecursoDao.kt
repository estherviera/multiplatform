package com.example.nexora.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecursoDao {
    @Query("SELECT * FROM recursos WHERE empresaId = :empresaId")
    fun porEmpresa(empresaId: String): Flow<List<Recurso>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(recursos: List<Recurso>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarUno(recurso: Recurso)
}
