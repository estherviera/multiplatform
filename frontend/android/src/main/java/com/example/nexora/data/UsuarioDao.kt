package com.example.nexora.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

// DAO: aquí están las consultas a la tabla "usuarios"
@Dao
interface UsuarioDao {

    @Insert
    suspend fun insertar(usuario: Usuario)

    @Query("SELECT * FROM usuarios WHERE email = :email LIMIT 1")
    suspend fun buscarPorEmail(email: String): Usuario?
}
