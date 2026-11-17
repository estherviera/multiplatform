package com.example.nexora.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

// Repositorio: es el único sitio por el que los ViewModels acceden a los datos.
// Así las pantallas no saben si los datos vienen de Room, de DataStore o de archivos.
class NexoraRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDeDatos = NexoraDatabase.obtenerBaseDeDatos(appContext)
    private val usuarioDao = baseDeDatos.usuarioDao()
    private val videoDao = baseDeDatos.videoDao()
    private val sesion = SesionManager(appContext)

    // ---------- Usuarios y sesión ----------

    // Devuelve el usuario creado, o null si ese email ya estaba registrado
    suspend fun registrar(nombre: String, email: String, password: String): Usuario? {
        if (usuarioDao.buscarPorEmail(email) != null) return null
        usuarioDao.insertar(Usuario(nombre = nombre, email = email, passwordHash = calcularHash(password)))
        sesion.guardarSesion(email)
        return usuarioDao.buscarPorEmail(email)
    }

    // Devuelve el usuario si el email y la contraseña son correctos, o null si no
    suspend fun login(email: String, password: String): Usuario? {
        val usuario = usuarioDao.buscarPorEmail(email) ?: return null
        if (usuario.passwordHash != calcularHash(password)) return null
        sesion.guardarSesion(email)
        return usuario
    }

    // Si había una sesión guardada, devuelve ese usuario
    suspend fun usuarioConSesion(): Usuario? {
        val email = sesion.obtenerEmail() ?: return null
        return usuarioDao.buscarPorEmail(email)
    }

    suspend fun cerrarSesion() {
        sesion.cerrarSesion()
    }

    // ---------- Vídeos ----------

    fun obtenerVideos(): Flow<List<Video>> = videoDao.obtenerTodos()

    suspend fun subirVideo(uri: Uri, titulo: String, descripcion: String, autor: Usuario) {
        // Copiar un archivo es una operación de E/S, así que la hacemos en Dispatchers.IO
        val ruta = withContext(Dispatchers.IO) {
            val carpeta = File(appContext.filesDir, "videos")
            carpeta.mkdirs()
            val archivo = File(carpeta, "video_${System.currentTimeMillis()}.mp4")
            val entrada = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("No se ha podido abrir el vídeo")
            entrada.use { origen ->
                archivo.outputStream().use { destino -> origen.copyTo(destino) }
            }
            archivo.absolutePath
        }

        val video = Video(
            titulo = titulo,
            descripcion = descripcion,
            rutaArchivo = ruta,
            autorEmail = autor.email,
            autorNombre = autor.nombre,
            fecha = System.currentTimeMillis()
        )
        videoDao.insertar(video)
    }

    suspend fun borrarVideo(video: Video) {
        withContext(Dispatchers.IO) {
            File(video.rutaArchivo).delete()
        }
        videoDao.borrar(video)
    }

    // Convierte la contraseña en un hash SHA-256 para no guardarla en texto plano
    private fun calcularHash(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
