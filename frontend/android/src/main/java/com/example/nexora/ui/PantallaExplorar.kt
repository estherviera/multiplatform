package com.example.nexora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nexora.data.Curso
import com.example.nexora.data.Empresa

@Composable
fun PantallaExplorar(
    viewModel: NexoraViewModel,
    abrirEmpresa: (String) -> Unit,
    abrirCurso: (Int) -> Unit
) {
    val empresas by viewModel.empresas.collectAsState(initial = emptyList())
    val cursosEnProgreso by viewModel.cursosEnProgreso.collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // === HERO BANNER ===
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF5145C9), Color(0xFF8065EF), Color(0xFFA88AFA))
                        )
                    )
                    .padding(24.dp)
            ) {
                Column {
                    Text(
                        "APRENDE · CONECTA · CRECE",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Todo el conocimiento de tus comunidades, en un lugar.",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Accede a los cursos, recursos y comunidades de las empresas que más te interesan.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                }
            }
        }

        // === COMUNIDADES DESTACADAS ===
        item {
            Text(
                "Comunidades destacadas",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(empresas) { empresa ->
                    TarjetaEmpresa(empresa = empresa, onClick = { abrirEmpresa(empresa.id) })
                }
            }
        }

        // === CONTINÚA APRENDIENDO ===
        if (cursosEnProgreso.isNotEmpty()) {
            item {
                Text(
                    "Continúa aprendiendo",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            items(cursosEnProgreso.take(3)) { curso ->
                TarjetaCurso(curso = curso, onClick = { abrirCurso(curso.id) })
            }
        }
    }
}

// ── Tarjeta de empresa reutilizable ──
@Composable
fun TarjetaEmpresa(empresa: Empresa, onClick: () -> Unit) {
    val colores = empresa.colorGradiente.split(",").map { Color(android.graphics.Color.parseColor("#$it")) }

    Card(
        modifier = Modifier
            .width(220.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        // Cabecera con gradiente y emoji
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .background(Brush.linearGradient(colores)),
            contentAlignment = Alignment.Center
        ) {
            Text(empresa.emoji, fontSize = 36.sp)
        }

        Column(modifier = Modifier.padding(14.dp)) {
            // Etiqueta de categoría
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFEEECFF)
            ) {
                Text(
                    empresa.categoria,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF6255D3)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(empresa.nombre, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                empresa.descripcion,
                fontSize = 12.sp,
                color = Color.Gray,
                maxLines = 2
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "♧ ${empresa.miembros} miembros",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
}

// ── Tarjeta de curso reutilizable ──
@Composable
fun TarjetaCurso(curso: Curso, onClick: () -> Unit) {
    val colores = curso.gradiente.split(",").map { Color(android.graphics.Color.parseColor("#$it")) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // Icono con gradiente
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(colores)),
                contentAlignment = Alignment.Center
            ) {
                Text(curso.emoji, fontSize = 26.sp)
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(curso.titulo, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(curso.descripcion, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                Spacer(Modifier.height(8.dp))

                // Barra de progreso
                LinearProgressIndicator(
                    progress = { curso.progreso / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF20A98B),
                    trackColor = Color(0xFFECEEF5),
                )

                Spacer(Modifier.height(4.dp))
                Text("${curso.progreso}% completado", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}
