package com.example.nexora

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaReproductor(videoViewModel: VideoViewModel, videoId: String, volver: () -> Unit) {
    val video = videoViewModel.buscarVideo(videoId)
    val context = LocalContext.current

    // Creamos el reproductor (ExoPlayer) una sola vez
    val reproductor = remember {
        ExoPlayer.Builder(context).build().apply {
            if (video != null) {
                setMediaItem(MediaItem.fromUri(video.url))
                prepare()
                play()
            }
        }
    }

    // Cuando salimos de la pantalla liberamos el reproductor
    DisposableEffect(Unit) {
        onDispose {
            reproductor.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(video?.titulo ?: "Vídeo") },
                navigationIcon = {
                    IconButton(onClick = volver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (video == null) {
                Text("No se ha encontrado el vídeo", modifier = Modifier.padding(16.dp))
            } else {
                // PlayerView es una vista "clásica" de Android, por eso usamos AndroidView
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { player = reproductor } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(video.titulo, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Subido por " + video.autorEmail, fontSize = 12.sp)
                    Text(video.descripcion, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
