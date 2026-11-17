package com.example.nexora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.nexora.ui.AuthViewModel
import com.example.nexora.ui.PantallaInicio
import com.example.nexora.ui.PantallaLogin
import com.example.nexora.ui.PantallaPerfil
import com.example.nexora.ui.PantallaRegistro
import com.example.nexora.ui.PantallaReproductor
import com.example.nexora.ui.PantallaSubir
import com.example.nexora.ui.VideoViewModel

// Única Activity de la app: todas las pantallas son funciones @Composable
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Colores del prototipo web de Nexora
            val colores = lightColorScheme(
                primary = Color(0xFF6656E8),
                secondary = Color(0xFF20A98B)
            )
            MaterialTheme(colorScheme = colores) {
                App()
            }
        }
    }
}

@Composable
fun App() {
    val authViewModel: AuthViewModel = viewModel()
    val videoViewModel: VideoViewModel = viewModel()
    val comprobandoSesion by authViewModel.comprobandoSesion.collectAsState()

    // Mientras miramos si había una sesión guardada, mostramos un círculo de carga
    if (comprobandoSesion) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        Navegacion(authViewModel, videoViewModel)
    }
}

// Aquí se definen todas las pantallas y cómo se pasa de una a otra
@Composable
fun Navegacion(authViewModel: AuthViewModel, videoViewModel: VideoViewModel) {
    val navController = rememberNavController()
    // Si ya había sesión iniciada empezamos en "inicio", si no en "login" (solo se decide una vez)
    val pantallaInicial = remember { if (authViewModel.usuario.value != null) "inicio" else "login" }

    NavHost(navController = navController, startDestination = pantallaInicial) {

        composable("login") {
            PantallaLogin(
                authViewModel = authViewModel,
                irARegistro = {
                    authViewModel.limpiarError()
                    navController.navigate("registro")
                },
                alEntrar = {
                    navController.navigate("inicio") {
                        popUpTo("login") { inclusive = true } // para que "atrás" no vuelva al login
                    }
                }
            )
        }

        composable("registro") {
            PantallaRegistro(
                authViewModel = authViewModel,
                volver = {
                    authViewModel.limpiarError()
                    navController.popBackStack()
                },
                alEntrar = {
                    navController.navigate("inicio") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        composable("inicio") {
            PantallaInicio(
                videoViewModel = videoViewModel,
                abrirVideo = { id -> navController.navigate("reproductor/$id") },
                irASubir = { navController.navigate("subir") },
                irAPerfil = { navController.navigate("perfil") }
            )
        }

        composable("subir") {
            PantallaSubir(
                videoViewModel = videoViewModel,
                authViewModel = authViewModel,
                volver = { navController.popBackStack() }
            )
        }

        composable("reproductor/{videoId}") { pantalla ->
            val videoId = pantalla.arguments?.getString("videoId")?.toIntOrNull() ?: 0
            PantallaReproductor(
                videoViewModel = videoViewModel,
                authViewModel = authViewModel,
                videoId = videoId,
                volver = { navController.popBackStack() }
            )
        }

        composable("perfil") {
            PantallaPerfil(
                authViewModel = authViewModel,
                videoViewModel = videoViewModel,
                volver = { navController.popBackStack() },
                alCerrarSesion = {
                    authViewModel.cerrarSesion()
                    navController.navigate("login") {
                        popUpTo(navController.graph.id) { inclusive = true } // borra todo el historial
                    }
                }
            )
        }
    }
}
