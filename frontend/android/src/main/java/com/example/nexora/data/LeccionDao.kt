package com.example.nexora.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LeccionDao {
    @Query("SELECT * FROM lecciones WHERE cursoId = :cursoId ORDER BY orden")
    fun porCurso(cursoId: Int): Flow<List<Leccion>>

    @Query("UPDATE lecciones SET completada = 1 WHERE id = :leccionId")
    suspend fun completar(leccionId: Int)

    @Query("SELECT COUNT(*) FROM lecciones WHERE cursoId = :cursoId")
    suspend fun totalPorCurso(cursoId: Int): Int

    @Query("SELECT COUNT(*) FROM lecciones WHERE cursoId = :cursoId AND completada = 1")
    suspend fun completadasPorCurso(cursoId: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(lecciones: List<Leccion>)
}
