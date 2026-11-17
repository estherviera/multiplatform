package com.example.nexora

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage

// Se encarga de los vídeos: cargar la lista y subir vídeos nuevos.
// - El archivo de vídeo se guarda en Firebase Storage.
// - Los datos (título, descripción, url...) se guardan en Firestore, en la colección "videos".
class VideoViewModel : ViewModel() {

    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    var videos by mutableStateOf(listOf<Video>())
        private set

    var subiendo by mutableStateOf(false)
        private set

    var progreso by mutableStateOf(0f) // de 0.0 a 1.0
        private set

    var mensaje by mutableStateOf<String?>(null)
        private set

    fun cargarVideos() {
        db.collection("videos")
            .orderBy("fecha", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { resultado ->
                val lista = mutableListOf<Video>()
                for (documento in resultado.documents) {
                    val video = documento.toObject(Video::class.java)
                    if (video != null) {
                        lista.add(video.copy(id = documento.id))
                    }
                }
                videos = lista
            }
            .addOnFailureListener {
                mensaje = "No se pudieron cargar los vídeos"
            }
    }

    fun subirVideo(uri: Uri, titulo: String, descripcion: String) {
        if (titulo.isBlank()) {
            mensaje = "Ponle un título al vídeo"
            return
        }
        val usuario = FirebaseAuth.getInstance().currentUser ?: return

        subiendo = true
        progreso = 0f
        mensaje = null

        // Nombre único para el archivo usando la hora actual
        val archivo = storage.reference.child("videos/${System.currentTimeMillis()}.mp4")

        // 1. Subimos el archivo a Storage
        archivo.putFile(uri)
            .addOnProgressListener { tarea ->
                progreso = tarea.bytesTransferred.toFloat() / tarea.totalByteCount
            }
            .addOnSuccessListener {
                // 2. Pedimos la URL pública del vídeo subido
                archivo.downloadUrl.addOnSuccessListener { url ->
                    // 3. Guardamos los datos del vídeo en Firestore
                    val video = Video(
                        titulo = titulo.trim(),
                        descripcion = descripcion.trim(),
                        url = url.toString(),
                        autorEmail = usuario.email ?: "",
                        fecha = System.currentTimeMillis()
                    )
                    db.collection("videos").add(video)
                        .addOnSuccessListener {
                            subiendo = false
                            mensaje = "¡Vídeo subido correctamente!"
                            cargarVideos()
                        }
                        .addOnFailureListener {
                            subiendo = false
                            mensaje = "Error al guardar los datos del vídeo"
                        }
                }
            }
            .addOnFailureListener {
                subiendo = false
                mensaje = "Error al subir el vídeo: ${it.message}"
            }
    }

    fun buscarVideo(id: String): Video? {
        return videos.find { it.id == id }
    }

    fun limpiarMensaje() {
        mensaje = null
    }
}
