package com.example.nexora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

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

// Aquí se definen todas las pantallas y cómo se navega entre ellas
@Composable
fun App() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val videoViewModel: VideoViewModel = viewModel()

    // Si ya hay sesión iniciada empezamos en "inicio", si no en "login"
    val pantallaInicial = remember { if (authViewModel.usuario != null) "inicio" else "login" }

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
                volver = { navController.popBackStack() }
            )
        }

        composable("reproductor/{videoId}") { pantalla ->
            val videoId = pantalla.arguments?.getString("videoId") ?: ""
            PantallaReproductor(
                videoViewModel = videoViewModel,
                videoId = videoId,
                volver = { navController.popBackStack() }
            )
        }

        composable("perfil") {
            PantallaPerfil(
                authViewModel = authViewModel,
                volver = { navController.popBackStack() },
                alCerrarSesion = {
                    navController.navigate("login") {
                        popUpTo(navController.graph.id) { inclusive = true } // borra todo el historial
                    }
                }
            )
        }
    }
}
