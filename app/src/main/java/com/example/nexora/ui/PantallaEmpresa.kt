package com.example.nexora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import com.example.nexora.data.Curso
import com.example.nexora.data.Empresa
import com.example.nexora.data.Publicacion
import com.example.nexora.data.Recurso

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaEmpresa(
    empresaId: String,
    viewModel: NexoraViewModel,
    abrirCurso: (Int) -> Unit,
    volver: () -> Unit
) {
    val empresa by viewModel.empresaActual.collectAsState()
    val cursos by viewModel.cursosPorEmpresa(empresaId).collectAsState(initial = emptyList())
    val recursos by viewModel.recursosPorEmpresa(empresaId).collectAsState(initial = emptyList())
    val publicaciones by viewModel.publicacionesPorEmpresa(empresaId).collectAsState(initial = emptyList())
    var pestanaSeleccionada by remember { mutableIntStateOf(0) }
    val pestanas = listOf("Cursos", "Recursos", "Comunidad", "Miembros")

    LaunchedEffect(empresaId) {
        viewModel.seleccionarEmpresa(empresaId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(empresa?.nombre ?: "") },
                navigationIcon = {
                    IconButton(onClick = volver) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // === CABECERA DE EMPRESA ===
            item {
                empresa?.let { emp ->
                    val colores = emp.colorGradiente.split(",")
                        .map { Color(android.graphics.Color.parseColor("#$it")) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(Brush.linearGradient(colores)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emp.emoji, fontSize = 52.sp)
                    }

                    Column(modifier = Modifier.padding(16.dp)) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFEEECFF)
                        ) {
                            Text(
                                emp.categoria,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF6255D3)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(emp.nombre, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(emp.descripcion, fontSize = 14.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Text("♧ ${emp.miembros} miembros", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }

            // === PESTAÑAS ===
            item {
                TabRow(
                    selectedTabIndex = pestanaSeleccionada,
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFF6656E8)
                ) {
                    pestanas.forEachIndexed { index, titulo ->
                        Tab(
                            selected = pestanaSeleccionada == index,
                            onClick = { pestanaSeleccionada = index },
                            text = {
                                Text(
                                    titulo,
                                    fontWeight = if (pestanaSeleccionada == index) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        )
                    }
                }
            }

            // === CONTENIDO SEGÚN PESTAÑA ===
            when (pestanaSeleccionada) {
                0 -> { // Cursos
                    items(cursos) { curso ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            TarjetaCurso(curso = curso, onClick = { abrirCurso(curso.id) })
                        }
                    }
                    if (cursos.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No hay cursos disponibles aún", color = Color.Gray)
                            }
                        }
                    }
                }

                1 -> { // Recursos
                    items(recursos) { recurso ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val iconoTipo = when (recurso.tipo) {
                                    "pdf" -> "📄"
                                    "video" -> "🎬"
                                    "enlace" -> "🔗"
                                    else -> "📁"
                                }
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFF3F1FF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(iconoTipo, fontSize = 22.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(recurso.titulo, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(
                                        "${recurso.tipo.uppercase()} · ${recurso.tamano}",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                    if (recursos.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No hay recursos disponibles", color = Color.Gray)
                            }
                        }
                    }
                }

                2 -> { // Comunidad
                    items(publicaciones) { pub ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFE8E5FF)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            pub.autor.take(1).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF6656E8),
                                            fontSize = 15.sp
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(pub.autor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(pub.fecha, fontSize = 11.sp, color = Color.Gray)
                                    }
                                }

                                Spacer(Modifier.height(10.dp))
                                Text(pub.titulo, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(pub.texto, fontSize = 13.sp, color = Color.DarkGray)
                                Spacer(Modifier.height(10.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.FavoriteBorder,
                                        contentDescription = "Me gusta",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("${pub.likes}", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                    if (publicaciones.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Aún no hay publicaciones", color = Color.Gray)
                            }
                        }
                    }
                }

                3 -> { // Miembros
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(24.dp))
                            Text("👥", fontSize = 48.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "${empresa?.miembros ?: 0} miembros",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Los miembros de esta comunidad comparten conocimiento y se ayudan mutuamente.",
                                fontSize = 14.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
