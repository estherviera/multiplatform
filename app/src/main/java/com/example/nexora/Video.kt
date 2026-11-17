package com.example.nexora

// Datos de un vídeo tal y como se guardan en Firestore.
// Todos los campos tienen valor por defecto para que Firestore pueda crear el objeto.
data class Video(
    val id: String = "",
    val titulo: String = "",
    val descripcion: String = "",
    val url: String = "",
    val autorEmail: String = "",
    val fecha: Long = 0
)
