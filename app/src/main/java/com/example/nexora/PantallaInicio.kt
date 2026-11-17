package com.example.nexora

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaInicio(
    videoViewModel: VideoViewModel,
    abrirVideo: (String) -> Unit,
    irASubir: () -> Unit,
    irAPerfil: () -> Unit
) {
    // Al abrir la pantalla cargamos los vídeos de Firestore
    LaunchedEffect(Unit) {
        videoViewModel.cargarVideos()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nexora", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = irAPerfil) {
                        Icon(Icons.Filled.Person, contentDescription = "Perfil")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = irASubir) {
                Icon(Icons.Filled.Add, contentDescription = "Subir vídeo")
            }
        }
    ) { padding ->
        if (videoViewModel.videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Todavía no hay vídeos. ¡Sube el primero con el botón +!")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(videoViewModel.videos) { video ->
                    TarjetaVideo(video = video, alPulsar = { abrirVideo(video.id) })
                }
            }
        }
    }
}

@Composable
fun TarjetaVideo(video: Video, alPulsar: () -> Unit) {
    Card(
        onClick = alPulsar,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("▶  " + video.titulo, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (video.descripcion.isNotBlank()) {
                Text(video.descripcion, maxLines = 2)
            }
            Text(
                "Subido por " + video.autorEmail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
