package com.example.nexora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.nexora.data.NexoraRepository
import com.example.nexora.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6656E8),
                    secondary = Color(0xFF20A98B),
                    background = Color(0xFFF8F7FC),
                    surface = Color.White
                )
            ) {
                val context = LocalContext.current
                val repo = remember { NexoraRepository(context) }
                val authViewModel: AuthViewModel = viewModel()
                val videoViewModel: VideoViewModel = viewModel()
                val nexoraViewModel: NexoraViewModel = viewModel()
                val sesionIniciada by authViewModel.sesionIniciada.collectAsState()

                if (sesionIniciada) {
                    AppPrincipal(
                        authViewModel = authViewModel,
                        videoViewModel = videoViewModel,
                        nexoraViewModel = nexoraViewModel
                    )
                } else {
                    PantallaLogin(
                        viewModel = authViewModel,
                        onRegistro = {}
                    )
                }
            }
        }
    }
}

// === Destinos del bottom bar ===
sealed class Destino(val ruta: String, val titulo: String, val icono: ImageVector) {
    data object Explorar : Destino("explorar", "Inicio", Icons.Default.Home)
    data object Empresas : Destino("empresas", "Comunidades", Icons.Default.Star)
    data object MisCursos : Destino("mis_cursos", "Mis Cursos", Icons.Default.PlayArrow)
    data object Admin : Destino("admin", "Admin", Icons.Default.Settings)
    data object Perfil : Destino("perfil", "Perfil", Icons.Default.Person)
}

@Composable
fun AppPrincipal(
    authViewModel: AuthViewModel,
    videoViewModel: VideoViewModel,
    nexoraViewModel: NexoraViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val rutaActual = navBackStackEntry?.destination?.route

    val destinos = listOf(
        Destino.Explorar,
        Destino.Empresas,
        Destino.MisCursos,
        Destino.Admin,
        Destino.Perfil
    )

    // Ocultar bottom bar en pantallas de detalle
    val mostrarBottomBar = rutaActual in destinos.map { it.ruta }

    Scaffold(
        bottomBar = {
            if (mostrarBottomBar) {
                NavigationBar(
                    containerColor = Color.White,
                    tonalElevation = 8.dp
                ) {
                    destinos.forEach { destino ->
                        NavigationBarItem(
                            icon = { Icon(destino.icono, contentDescription = destino.titulo) },
                            label = { Text(destino.titulo, fontSize = 10.sp) },
                            selected = rutaActual == destino.ruta,
                            onClick = {
                                navController.navigate(destino.ruta) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF6656E8),
                                selectedTextColor = Color(0xFF6656E8),
                                indicatorColor = Color(0xFFEEECFF)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "explorar",
            modifier = Modifier.padding(innerPadding)
        ) {
            // Pantallas principales (bottom bar)
            composable("explorar") {
                PantallaExplorar(
                    viewModel = nexoraViewModel,
                    abrirEmpresa = { id -> navController.navigate("empresa/$id") },
                    abrirCurso = { id -> navController.navigate("curso/$id") }
                )
            }

            composable("empresas") {
                PantallaEmpresas(
                    viewModel = nexoraViewModel,
                    abrirEmpresa = { id -> navController.navigate("empresa/$id") }
                )
            }

            composable("mis_cursos") {
                PantallaMisCursos(
                    viewModel = nexoraViewModel,
                    abrirCurso = { id -> navController.navigate("curso/$id") }
                )
            }

            composable("admin") {
                PantallaAdmin(viewModel = nexoraViewModel)
            }

            composable("perfil") {
                PantallaPerfil(
                    viewModel = authViewModel,
                    onLogout = {}
                )
            }

            // Pantallas de detalle (sin bottom bar)
            composable(
                "empresa/{empresaId}",
                arguments = listOf(navArgument("empresaId") { type = NavType.StringType })
            ) { backStackEntry ->
                val empresaId = backStackEntry.arguments?.getString("empresaId") ?: ""
                PantallaEmpresa(
                    empresaId = empresaId,
                    viewModel = nexoraViewModel,
                    abrirCurso = { id -> navController.navigate("curso/$id") },
                    volver = { navController.popBackStack() }
                )
            }

            composable(
                "curso/{cursoId}",
                arguments = listOf(navArgument("cursoId") { type = NavType.IntType })
            ) { backStackEntry ->
                val cursoId = backStackEntry.arguments?.getInt("cursoId") ?: 0
                PantallaCurso(
                    cursoId = cursoId,
                    viewModel = nexoraViewModel,
                    volver = { navController.popBackStack() }
                )
            }

            // Pantallas heredadas del proyecto original de vídeos
            composable("subir") {
                PantallaSubir(viewModel = videoViewModel)
            }

            composable(
                "reproductor/{videoId}",
                arguments = listOf(navArgument("videoId") { type = NavType.IntType })
            ) { backStackEntry ->
                val videoId = backStackEntry.arguments?.getInt("videoId") ?: 0
                PantallaReproductor(videoId = videoId, viewModel = videoViewModel)
            }
        }
    }
}
