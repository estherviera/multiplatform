package com.example.nexora

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

// Se encarga de todo lo relacionado con el usuario: iniciar sesión, registrarse y cerrar sesión.
class AuthViewModel : ViewModel() {

    private val auth = FirebaseAuth.getInstance()

    // Si ya había iniciado sesión antes, Firebase lo recuerda
    var usuario by mutableStateOf<FirebaseUser?>(auth.currentUser)
        private set

    var cargando by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            error = "Rellena todos los campos"
            return
        }
        cargando = true
        error = null
        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener {
                usuario = auth.currentUser
                cargando = false
            }
            .addOnFailureListener {
                error = "Email o contraseña incorrectos"
                cargando = false
            }
    }

    fun registrar(email: String, password: String, password2: String) {
        if (email.isBlank() || password.isBlank()) {
            error = "Rellena todos los campos"
            return
        }
        if (password.length < 6) {
            error = "La contraseña debe tener al menos 6 caracteres"
            return
        }
        if (password != password2) {
            error = "Las contraseñas no coinciden"
            return
        }
        cargando = true
        error = null
        auth.createUserWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener {
                // Al registrarse, Firebase ya deja la sesión iniciada
                usuario = auth.currentUser
                cargando = false
            }
            .addOnFailureListener {
                error = "No se pudo crear la cuenta: ${it.message}"
                cargando = false
            }
    }

    fun cerrarSesion() {
        auth.signOut()
        usuario = null
    }

    fun limpiarError() {
        error = null
    }
}
