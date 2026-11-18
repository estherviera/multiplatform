package com.example.nexora.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CursoDao {
    @Query("SELECT * FROM cursos")
    fun todos(): Flow<List<Curso>>

    @Query("SELECT * FROM cursos WHERE empresaId = :empresaId")
    fun porEmpresa(empresaId: String): Flow<List<Curso>>

    @Query("SELECT * FROM cursos WHERE progreso > 0")
    fun enProgreso(): Flow<List<Curso>>

    @Query("SELECT * FROM cursos WHERE id = :id")
    suspend fun porId(id: Int): Curso?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(cursos: List<Curso>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarUno(curso: Curso): Long

    @Update
    suspend fun actualizar(curso: Curso)

    @Query("UPDATE cursos SET progreso = :progreso WHERE id = :cursoId")
    suspend fun actualizarProgreso(cursoId: Int, progreso: Int)
}
