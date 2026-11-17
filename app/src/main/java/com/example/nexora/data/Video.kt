package com.example.nexora.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Tabla "videos" de la base de datos Room.
// El archivo de vídeo se guarda en el almacenamiento interno de la app
// y aquí solo guardamos su ruta y sus datos.
@Entity(tableName = "videos")
data class Video(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val titulo: String,
    val descripcion: String,
    val rutaArchivo: String,
    val autorEmail: String,
    val autorNombre: String,
    val fecha: Long
)
