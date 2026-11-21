package com.example.nexora.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "empresas")
data class Empresa(
    @PrimaryKey val id: String,
    val nombre: String,
    val categoria: String,
    val descripcion: String,
    val emoji: String,
    val colorGradiente: String, // "hex1,hex2" para el gradiente
    val miembros: Int
)
