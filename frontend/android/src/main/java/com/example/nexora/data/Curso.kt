package com.example.nexora.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cursos")
data class Curso(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val empresaId: String,
    val titulo: String,
    val descripcion: String,
    val emoji: String,
    val gradiente: String, // "hex1,hex2"
    val progreso: Int = 0  // 0-100
)
