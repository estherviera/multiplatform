package com.example.nexora.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sesion")

// Guarda qué usuario tiene la sesión iniciada usando DataStore (clave-valor),
// así al cerrar y volver a abrir la app no hay que hacer login otra vez.
class SesionManager(private val context: Context) {

    private val claveEmail = stringPreferencesKey("email_usuario")

    suspend fun obtenerEmail(): String? {
        return context.dataStore.data.map { it[claveEmail] }.first()
    }

    suspend fun guardarSesion(email: String) {
        context.dataStore.edit { it[claveEmail] = email }
    }

    suspend fun cerrarSesion() {
        context.dataStore.edit { it.remove(claveEmail) }
    }
}
