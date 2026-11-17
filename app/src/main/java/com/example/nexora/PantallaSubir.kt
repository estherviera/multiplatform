package com.example.nexora

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaSubir(videoViewModel: VideoViewModel, volver: () -> Unit) {
    var titulo by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }
    var videoElegido by remember { mutableStateOf<Uri?>(null) }

    // Abre la galería del móvil para elegir un vídeo
    val selectorDeVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        videoElegido = uri
    }

    // Borramos mensajes antiguos al entrar en la pantalla
    LaunchedEffect(Unit) {
        videoViewModel.limpiarMensaje()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subir vídeo") },
                navigationIcon = {
                    IconButton(onClick = volver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            OutlinedButton(
                onClick = { selectorDeVideo.launch("video/*") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (videoElegido == null) "Elegir vídeo de la galería" else "✓ Vídeo elegido (pulsa para cambiar)")
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = titulo,
                onValueChange = { titulo = it },
                label = { Text("Título") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = descripcion,
                onValueChange = { descripcion = it },
                label = { Text("Descripción") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val uri = videoElegido
                    if (uri != null) {
                        videoViewModel.subirVideo(uri, titulo, descripcion)
                    }
                },
                enabled = videoElegido != null && !videoViewModel.subiendo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Subir vídeo")
            }

            if (videoViewModel.subiendo) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { videoViewModel.progreso },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Subiendo... ${(videoViewModel.progreso * 100).toInt()}%")
            }

            if (videoViewModel.mensaje != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(videoViewModel.mensaje!!)
            }
        }
    }
}
