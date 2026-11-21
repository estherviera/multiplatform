package com.example.nexora.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Repositorio: punto de acceso único a todos los datos.
 * Patrón Facade: oculta Room, DataStore y archivos a los ViewModels.
 */
class NexoraRepository(context: Context) {

    private val db = NexoraDatabase.getInstance(context).also { registrarInstancia(it) }
    private val usuarioDao = db.usuarioDao()
    private val videoDao = db.videoDao()
    private val empresaDao = db.empresaDao()
    private val cursoDao = db.cursoDao()
    private val leccionDao = db.leccionDao()
    private val recursoDao = db.recursoDao()
    private val publicacionDao = db.publicacionDao()
    val sesion = SesionManager(context)
    private val carpetaVideos = File(context.filesDir, "videos").also { it.mkdirs() }

    // ── Usuarios ──
    suspend fun registrar(nombre: String, email: String, password: String): Boolean {
        if (usuarioDao.buscarPorEmail(email) != null) return false
        val hash = sha256(password)
        usuarioDao.insertar(Usuario(nombre = nombre, email = email, passwordHash = hash))
        sesion.guardarSesion(email)
        return true
    }

    suspend fun login(email: String, password: String): Boolean {
        val user = usuarioDao.buscarPorEmail(email) ?: return false
        if (user.passwordHash != sha256(password)) return false
        sesion.guardarSesion(email)
        return true
    }

    suspend fun obtenerUsuario(email: String): Usuario? = usuarioDao.buscarPorEmail(email)

    // ── Vídeos ──
    fun todosLosVideos(): Flow<List<Video>> = videoDao.obtenerTodos()

    suspend fun subirVideo(titulo: String, descripcion: String, stream: InputStream, email: String, nombre: String): Boolean {
        val archivo = File(carpetaVideos, "${System.currentTimeMillis()}.mp4")
        archivo.outputStream().use { stream.copyTo(it) }
        videoDao.insertar(
            Video(
                titulo = titulo,
                descripcion = descripcion,
                rutaArchivo = archivo.absolutePath,
                autorEmail = email,
                autorNombre = nombre,
                fecha = System.currentTimeMillis()
            )
        )
        return true
    }



    suspend fun borrarVideo(video: Video) {
        File(video.rutaArchivo).delete()
        videoDao.borrar(video)
    }

    // ── Empresas ──
    fun todasLasEmpresas(): Flow<List<Empresa>> = empresaDao.todas()
    suspend fun obtenerEmpresa(id: String): Empresa? = empresaDao.porId(id)

    // ── Cursos ──
    fun todosLosCursos(): Flow<List<Curso>> = cursoDao.todos()
    fun cursosPorEmpresa(empresaId: String): Flow<List<Curso>> = cursoDao.porEmpresa(empresaId)
    fun cursosEnProgreso(): Flow<List<Curso>> = cursoDao.enProgreso()
    suspend fun obtenerCurso(id: Int): Curso? = cursoDao.porId(id)

    suspend fun crearCurso(empresaId: String, titulo: String, descripcion: String, emoji: String): Long {
        return cursoDao.insertarUno(
            Curso(
                empresaId = empresaId,
                titulo = titulo,
                descripcion = descripcion,
                emoji = emoji,
                gradiente = obtenerEmpresa(empresaId)?.colorGradiente ?: "6656E8,8B7FFF"
            )
        )
    }

    // ── Lecciones ──
    fun leccionesPorCurso(cursoId: Int): Flow<List<Leccion>> = leccionDao.porCurso(cursoId)

    suspend fun completarLeccion(leccionId: Int, cursoId: Int) {
        leccionDao.completar(leccionId)
        // Actualizar el progreso del curso
        val total = leccionDao.totalPorCurso(cursoId)
        val completadas = leccionDao.completadasPorCurso(cursoId)
        val progreso = if (total > 0) (completadas * 100) / total else 0
        cursoDao.actualizarProgreso(cursoId, progreso)
    }

    // ── Recursos ──
    fun recursosPorEmpresa(empresaId: String): Flow<List<Recurso>> = recursoDao.porEmpresa(empresaId)

    suspend fun crearRecurso(empresaId: String, titulo: String, tipo: String, tamano: String) {
        recursoDao.insertarUno(
            Recurso(empresaId = empresaId, titulo = titulo, tipo = tipo, tamano = tamano)
        )
    }

    // ── Publicaciones ──
    fun publicacionesPorEmpresa(empresaId: String): Flow<List<Publicacion>> = publicacionDao.porEmpresa(empresaId)

    suspend fun crearPublicacion(empresaId: String, autor: String, titulo: String, texto: String) {
        publicacionDao.insertarUna(
            Publicacion(empresaId = empresaId, autor = autor, titulo = titulo, texto = texto)
        )
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
