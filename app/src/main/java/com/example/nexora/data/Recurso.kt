package com.example.nexora.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recursos")
data class Recurso(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val empresaId: String,
    val titulo: String,
    val tipo: String,   // PDF, ZIP, VÍDEO, DOCX
    val tamano: String  // "2,4 MB"
)
