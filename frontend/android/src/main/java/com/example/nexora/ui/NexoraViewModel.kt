package com.example.nexora.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nexora.data.Curso
import com.example.nexora.data.Empresa
import com.example.nexora.data.Leccion
import com.example.nexora.data.NexoraRepository
import com.example.nexora.data.Publicacion
import com.example.nexora.data.Recurso
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel principal de la plataforma.
 * Gestiona empresas, cursos, lecciones, recursos y publicaciones.
 * Usa StateFlow (patrón Observer) para que las pantallas reaccionen a los cambios.
 */
class NexoraViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NexoraRepository(app)

    // Flujos observables (Flow de Room → se actualizan automáticamente)
    val empresas: Flow<List<Empresa>> = repo.todasLasEmpresas()
    val cursos: Flow<List<Curso>> = repo.todosLosCursos()
    val cursosEnProgreso: Flow<List<Curso>> = repo.cursosEnProgreso()

    // Estado de la empresa seleccionada
    private val _empresaActual = MutableStateFlow<Empresa?>(null)
    val empresaActual: StateFlow<Empresa?> = _empresaActual

    // Estado del curso seleccionado
    private val _cursoActual = MutableStateFlow<Curso?>(null)
    val cursoActual: StateFlow<Curso?> = _cursoActual

    private val _leccionActual = MutableStateFlow(0)
    val leccionActual: StateFlow<Int> = _leccionActual

    // Toast / mensajes
    private val _mensaje = MutableStateFlow<String?>(null)
    val mensaje: StateFlow<String?> = _mensaje

    fun limpiarMensaje() { _mensaje.value = null }

    // ── Empresas ──
    fun seleccionarEmpresa(id: String) {
        viewModelScope.launch {
            _empresaActual.value = repo.obtenerEmpresa(id)
        }
    }

    // ── Cursos ──
    fun cursosPorEmpresa(empresaId: String): Flow<List<Curso>> = repo.cursosPorEmpresa(empresaId)

    fun seleccionarCurso(id: Int) {
        viewModelScope.launch {
            _cursoActual.value = repo.obtenerCurso(id)
            _leccionActual.value = 0
        }
    }

    fun cambiarLeccion(indice: Int) {
        _leccionActual.value = indice
    }

    fun leccionesPorCurso(cursoId: Int): Flow<List<Leccion>> = repo.leccionesPorCurso(cursoId)

    fun completarLeccion(leccionId: Int, cursoId: Int) {
        viewModelScope.launch {
            repo.completarLeccion(leccionId, cursoId)
            // Recargar el curso para actualizar el progreso
            _cursoActual.value = repo.obtenerCurso(cursoId)
            _mensaje.value = "¡Progreso actualizado!"
        }
    }

    // ── Recursos ──
    fun recursosPorEmpresa(empresaId: String): Flow<List<Recurso>> = repo.recursosPorEmpresa(empresaId)

    fun crearRecurso(empresaId: String, titulo: String, tipo: String, tamano: String = "Demo") {
        viewModelScope.launch {
            repo.crearRecurso(empresaId, titulo, tipo, tamano)
            _mensaje.value = "Recurso añadido a la demostración"
        }
    }

    // ── Publicaciones ──
    fun publicacionesPorEmpresa(empresaId: String): Flow<List<Publicacion>> = repo.publicacionesPorEmpresa(empresaId)

    fun crearPublicacion(empresaId: String, titulo: String, texto: String) {
        viewModelScope.launch {
            repo.crearPublicacion(empresaId, "Tú · Miembro", titulo, texto)
            _mensaje.value = "Publicación añadida"
        }
    }

    // ── Admin ──
    fun crearCurso(empresaId: String, titulo: String, descripcion: String, emoji: String = "📚") {
        viewModelScope.launch {
            repo.crearCurso(empresaId, titulo, descripcion, emoji)
            _mensaje.value = "Curso creado en la demostración"
        }
    }
}
