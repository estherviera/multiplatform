package com.example.nexora.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaReproductor(
    videoViewModel: VideoViewModel,
    authViewModel: AuthViewModel,
    videoId: Int,
    volver: () -> Unit
) {
    val videos by videoViewModel.videos.collectAsState(initial = emptyList())
    val usuario by authViewModel.usuario.collectAsState()
    val video = videos.find { it.id == videoId }
    val context = LocalContext.current

    // Creamos el reproductor (ExoPlayer) una sola vez
    val reproductor = remember { ExoPlayer.Builder(context).build() }

    // Cuando ya tenemos el vídeo, le decimos al reproductor qué archivo tiene que reproducir
    LaunchedEffect(video?.rutaArchivo) {
        if (video != null) {
            reproductor.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(video.rutaArchivo))))
            reproductor.prepare()
            reproductor.play()
        }
    }

    // Callback del ciclo de vida: si la app pasa a segundo plano (onStop) pausamos el vídeo
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        reproductor.pause()
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
                },
                actions = {
                    // Solo quien ha subido el vídeo puede borrarlo
                    if (video != null && video.autorEmail == usuario?.email) {
                        IconButton(onClick = {
                            videoViewModel.borrarVideo(video)
                            volver()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Borrar vídeo")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (video == null) {
                Text("Cargando vídeo...", modifier = Modifier.padding(16.dp))
            } else {
                // PlayerView es una vista "clásica" (XML) de Android, por eso usamos AndroidView
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { player = reproductor } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(video.titulo, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Subido por " + video.autorNombre, fontSize = 12.sp)
                    Text(video.descripcion, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
