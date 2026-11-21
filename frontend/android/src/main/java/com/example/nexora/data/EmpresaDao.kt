package com.example.nexora.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EmpresaDao {
    @Query("SELECT * FROM empresas")
    fun todas(): Flow<List<Empresa>>

    @Query("SELECT * FROM empresas WHERE id = :id")
    suspend fun porId(id: String): Empresa?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(empresas: List<Empresa>)
}
