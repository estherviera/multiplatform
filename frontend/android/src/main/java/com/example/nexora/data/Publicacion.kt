package com.example.nexora.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "publicaciones")
data class Publicacion(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val empresaId: String,
    val autor: String,
    val titulo: String,
    val texto: String,
    val likes: Int = 0,
    val fecha: Long = System.currentTimeMillis()
)
