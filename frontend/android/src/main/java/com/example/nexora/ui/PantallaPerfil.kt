package com.example.nexora.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaPerfil(
    authViewModel: AuthViewModel,
    videoViewModel: VideoViewModel,
    volver: () -> Unit,
    alCerrarSesion: () -> Unit
) {
    val usuario by authViewModel.usuario.collectAsState()
    val videos by videoViewModel.videos.collectAsState(initial = emptyList())
    val misVideos = videos.count { it.autorEmail == usuario?.email }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mi perfil") },
                navigationIcon = {
                    IconButton(onClick = volver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(usuario?.nombre ?: "", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(usuario?.email ?: "")
            Spacer(modifier = Modifier.height(16.dp))
            Text("Vídeos subidos: $misVideos")
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = alCerrarSesion, modifier = Modifier.fillMaxWidth()) {
                Text("Cerrar sesión")
            }
        }
    }
}
