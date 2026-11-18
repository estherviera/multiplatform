package com.example.nexora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaAdmin(
    viewModel: NexoraViewModel
) {
    val empresas by viewModel.empresas.collectAsState(initial = emptyList())
    val mensaje by viewModel.mensaje.collectAsState()

    // Formulario de nuevo curso
    var empresaSeleccionada by remember { mutableStateOf("") }
    var tituloCurso by remember { mutableStateOf("") }
    var descripcionCurso by remember { mutableStateOf("") }
    var emojiCurso by remember { mutableStateOf("📘") }
    var expandidoEmpresa by remember { mutableStateOf(false) }

    // Formulario de nuevo recurso
    var empresaRecurso by remember { mutableStateOf("") }
    var tituloRecurso by remember { mutableStateOf("") }
    var tipoRecurso by remember { mutableStateOf("pdf") }
    var tamanoRecurso by remember { mutableStateOf("") }
    var expandidoEmpresaRec by remember { mutableStateOf(false) }

    var pestana by remember { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Panel de Administración", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Crea cursos y recursos para las comunidades", fontSize = 14.sp, color = Color.Gray)
        }

        // Pestañas Curso / Recurso
        item {
            TabRow(
                selectedTabIndex = pestana,
                containerColor = Color.Transparent,
                contentColor = Color(0xFF6656E8)
            ) {
                Tab(selected = pestana == 0, onClick = { pestana = 0 }) {
                    Text("Nuevo Curso", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
                }
                Tab(selected = pestana == 1, onClick = { pestana = 1 }) {
                    Text("Nuevo Recurso", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Mensaje de feedback
        if (mensaje.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        mensaje,
                        modifier = Modifier.padding(14.dp),
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        when (pestana) {
            0 -> {
                // === FORMULARIO CREAR CURSO ===
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("Crear nuevo curso", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Spacer(Modifier.height(16.dp))

                            // Selector de empresa
                            ExposedDropdownMenuBox(
                                expanded = expandidoEmpresa,
                                onExpandedChange = { expandidoEmpresa = !expandidoEmpresa }
                            ) {
                                OutlinedTextField(
                                    value = empresas.find { it.id == empresaSeleccionada }?.nombre ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Comunidad") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandidoEmpresa) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = expandidoEmpresa,
                                    onDismissRequest = { expandidoEmpresa = false }
                                ) {
                                    empresas.forEach { emp ->
                                        DropdownMenuItem(
                                            text = { Text("${emp.emoji} ${emp.nombre}") },
                                            onClick = {
                                                empresaSeleccionada = emp.id
                                                expandidoEmpresa = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = tituloCurso,
                                onValueChange = { tituloCurso = it },
                                label = { Text("Título del curso") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = descripcionCurso,
                                onValueChange = { descripcionCurso = it },
                                label = { Text("Descripción") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                minLines = 2
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = emojiCurso,
                                onValueChange = { emojiCurso = it },
                                label = { Text("Emoji") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    if (empresaSeleccionada.isNotEmpty() && tituloCurso.isNotEmpty()) {
                                        viewModel.crearCurso(
                                            empresaId = empresaSeleccionada,
                                            titulo = tituloCurso,
                                            descripcion = descripcionCurso,
                                            emoji = emojiCurso
                                        )
                                        tituloCurso = ""
                                        descripcionCurso = ""
                                        emojiCurso = "📘"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6656E8))
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Crear Curso", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            1 -> {
                // === FORMULARIO CREAR RECURSO ===
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("Crear nuevo recurso", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Spacer(Modifier.height(16.dp))

                            ExposedDropdownMenuBox(
                                expanded = expandidoEmpresaRec,
                                onExpandedChange = { expandidoEmpresaRec = !expandidoEmpresaRec }
                            ) {
                                OutlinedTextField(
                                    value = empresas.find { it.id == empresaRecurso }?.nombre ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Comunidad") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandidoEmpresaRec) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = expandidoEmpresaRec,
                                    onDismissRequest = { expandidoEmpresaRec = false }
                                ) {
                                    empresas.forEach { emp ->
                                        DropdownMenuItem(
                                            text = { Text("${emp.emoji} ${emp.nombre}") },
                                            onClick = {
                                                empresaRecurso = emp.id
                                                expandidoEmpresaRec = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = tituloRecurso,
                                onValueChange = { tituloRecurso = it },
                                label = { Text("Título del recurso") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = tipoRecurso,
                                onValueChange = { tipoRecurso = it },
                                label = { Text("Tipo (pdf, video, enlace)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = tamanoRecurso,
                                onValueChange = { tamanoRecurso = it },
                                label = { Text("Tamaño (ej: 2.4 MB)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    if (empresaRecurso.isNotEmpty() && tituloRecurso.isNotEmpty()) {
                                        viewModel.crearRecurso(
                                            empresaId = empresaRecurso,
                                            titulo = tituloRecurso,
                                            tipo = tipoRecurso,
                                            tamano = tamanoRecurso
                                        )
                                        tituloRecurso = ""
                                        tipoRecurso = "pdf"
                                        tamanoRecurso = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20A98B))
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Crear Recurso", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
