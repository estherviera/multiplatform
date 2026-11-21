package com.example.nexora.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lecciones")
data class Leccion(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val cursoId: Int,
    val titulo: String,
    val orden: Int,
    val completada: Boolean = false
)
