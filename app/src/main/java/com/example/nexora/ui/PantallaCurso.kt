package com.example.nexora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nexora.data.Leccion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaCurso(
    cursoId: Int,
    viewModel: NexoraViewModel,
    volver: () -> Unit
) {
    val curso by viewModel.cursoActual.collectAsState()
    val lecciones by viewModel.leccionesPorCurso(cursoId).collectAsState(initial = emptyList())
    val leccionActiva by viewModel.leccionActual.collectAsState()

    LaunchedEffect(cursoId) {
        viewModel.seleccionarCurso(cursoId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(curso?.titulo ?: "Curso") },
                navigationIcon = {
                    IconButton(onClick = volver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // === ZONA DE CONTENIDO / VIDEO PLACEHOLDER ===
            curso?.let { c ->
                val colores = c.gradiente.split(",")
                    .map { Color(android.graphics.Color.parseColor("#$it")) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Brush.linearGradient(colores)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Botón de play
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Reproducir",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            leccionActiva?.titulo ?: "Selecciona una lección",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // === PROGRESO ===
            curso?.let { c ->
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Progreso del curso", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${c.progreso}%", fontWeight = FontWeight.Bold, color = Color(0xFF20A98B))
                    }

                    Spacer(Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { c.progreso / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF20A98B),
                        trackColor = Color(0xFFECEEF5),
                    )
                }
            }

            Divider(color = Color(0xFFF0F0F0))

            // === LISTA DE LECCIONES ===
            Text(
                "Lecciones",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                itemsIndexed(lecciones) { index, leccion ->
                    val esActiva = leccionActiva?.id == leccion.id

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { viewModel.cambiarLeccion(leccion) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (esActiva) Color(0xFFF3F1FF) else Color.White
                        ),
                        elevation = CardDefaults.cardElevation(if (esActiva) 4.dp else 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Número de lección
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (leccion.completada) Color(0xFF20A98B)
                                        else if (esActiva) Color(0xFF6656E8)
                                        else Color(0xFFECEEF5)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (leccion.completada) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Completada",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Text(
                                        "${leccion.orden}",
                                        color = if (esActiva) Color.White else Color.Gray,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    leccion.titulo,
                                    fontWeight = if (esActiva) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = if (esActiva) Color(0xFF6656E8) else Color.Black
                                )
                                Text(
                                    if (leccion.completada) "✓ Completada" else "Pendiente",
                                    fontSize = 11.sp,
                                    color = if (leccion.completada) Color(0xFF20A98B) else Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            // === BOTÓN COMPLETAR ===
            leccionActiva?.let { leccion ->
                if (!leccion.completada) {
                    Button(
                        onClick = { viewModel.completarLeccion(leccion.id, cursoId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20A98B))
                    ) {
                        Text(
                            "Completar lección",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
