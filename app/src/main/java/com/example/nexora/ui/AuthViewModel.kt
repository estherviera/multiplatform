package com.example.nexora.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nexora.data.NexoraRepository
import com.example.nexora.data.Usuario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ViewModel de inicio de sesión, registro y cierre de sesión.
// Las pantallas observan los StateFlow y se redibujan solas cuando cambian.
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val repositorio = NexoraRepository(application)

    private val _usuario = MutableStateFlow<Usuario?>(null)
    val usuario: StateFlow<Usuario?> = _usuario

    // true mientras comprobamos si había una sesión guardada al abrir la app
    private val _comprobandoSesion = MutableStateFlow(true)
    val comprobandoSesion: StateFlow<Boolean> = _comprobandoSesion

    private val _cargando = MutableStateFlow(false)
    val cargando: StateFlow<Boolean> = _cargando

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch {
            _usuario.value = repositorio.usuarioConSesion()
            _comprobandoSesion.value = false
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _error.value = "Rellena todos los campos"
            return
        }
        viewModelScope.launch {
            _cargando.value = true
            val usuario = repositorio.login(email.trim(), password)
            if (usuario == null) {
                _error.value = "Email o contraseña incorrectos"
            } else {
                _error.value = null
                _usuario.value = usuario
            }
            _cargando.value = false
        }
    }

    fun registrar(nombre: String, email: String, password: String, password2: String) {
        if (nombre.isBlank() || email.isBlank() || password.isBlank()) {
            _error.value = "Rellena todos los campos"
            return
        }
        if (!email.contains("@")) {
            _error.value = "El email no es válido"
            return
        }
        if (password.length < 6) {
            _error.value = "La contraseña debe tener al menos 6 caracteres"
            return
        }
        if (password != password2) {
            _error.value = "Las contraseñas no coinciden"
            return
        }
        viewModelScope.launch {
            _cargando.value = true
            val usuario = repositorio.registrar(nombre.trim(), email.trim(), password)
            if (usuario == null) {
                _error.value = "Ya existe una cuenta con ese email"
            } else {
                _error.value = null
                _usuario.value = usuario
            }
            _cargando.value = false
        }
    }

    fun cerrarSesion() {
        _usuario.value = null // primero en pantalla, para que el login no nos devuelva a inicio
        viewModelScope.launch {
            repositorio.cerrarSesion()
        }
    }

    fun limpiarError() {
        _error.value = null
    }
}
