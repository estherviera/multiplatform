package com.example.nexora.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Tabla "usuarios" de la base de datos Room.
// El email es único: no puede haber dos cuentas con el mismo email.
@Entity(tableName = "usuarios", indices = [Index(value = ["email"], unique = true)])
data class Usuario(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nombre: String,
    val email: String,
    val passwordHash: String // nunca guardamos la contraseña tal cual, sino su hash
)
