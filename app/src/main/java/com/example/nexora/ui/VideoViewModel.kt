package com.example.nexora.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nexora.data.NexoraRepository
import com.example.nexora.data.Usuario
import com.example.nexora.data.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ViewModel de los vídeos: la lista, subir vídeos nuevos y borrarlos.
class VideoViewModel(application: Application) : AndroidViewModel(application) {

    private val repositorio = NexoraRepository(application)

    // Viene directamente de Room: se actualiza solo cuando se añade o borra un vídeo
    val videos: Flow<List<Video>> = repositorio.obtenerVideos()

    private val _subiendo = MutableStateFlow(false)
    val subiendo: StateFlow<Boolean> = _subiendo

    private val _mensaje = MutableStateFlow<String?>(null)
    val mensaje: StateFlow<String?> = _mensaje

    fun subirVideo(uri: Uri, titulo: String, descripcion: String, autor: Usuario) {
        if (titulo.isBlank()) {
            _mensaje.value = "Ponle un título al vídeo"
            return
        }
        viewModelScope.launch {
            _subiendo.value = true
            _mensaje.value = null
            try {
                repositorio.subirVideo(uri, titulo.trim(), descripcion.trim(), autor)
                _mensaje.value = "¡Vídeo subido correctamente!"
            } catch (e: Exception) {
                _mensaje.value = "Error al subir el vídeo: ${e.message}"
            }
            _subiendo.value = false
        }
    }

    fun borrarVideo(video: Video) {
        viewModelScope.launch {
            repositorio.borrarVideo(video)
        }
    }

    fun limpiarMensaje() {
        _mensaje.value = null
    }
}
