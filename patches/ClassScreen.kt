package com.fer.a53performance.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fer.a53performance.model.AppMode
import com.fer.a53performance.model.AppUiState
import com.fer.a53performance.ui.components.*
import com.fer.a53performance.ui.theme.A53Muted
import com.fer.a53performance.util.ExternalApps

@Composable
fun ClassScreen(
    context: Context,
    state: AppUiState,
    onActivateClass: () -> Unit,
    onSetMinutes: (Int) -> Unit,
    onStartFocus: () -> Unit,
    onStopFocus: () -> Unit,
    onNoteChange: (String) -> Unit,
    onDocumentPicked: (Uri, String) -> Unit,
    onNeedDndAccess: () -> Unit
) {
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        onDocumentPicked(uri, queryName(context, uri))
        ExternalApps.openDocument(context, uri)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)); ScreenHeader("Modo Clases", "Tu escritorio de estudio", "Todo lo que usás en clase, a uno o dos toques.") }
        item {
            GradientHero(Modifier.fillMaxWidth()) {
                Column {
                    Text("SESIÓN DE CLASE", color = A53Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(if (state.focusRunning) formatTimer(state.focusSecondsLeft) else "${state.focusMinutes}:00", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Text(if (state.focusRunning) "Foco en curso" else "Elegí duración y empezá", color = A53Muted)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(25, 50, 90).forEach { minutes ->
                            FilterChip(selected = state.focusMinutes == minutes, onClick = { onSetMinutes(minutes) }, label = { Text("$minutes min") }, enabled = !state.focusRunning)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onActivateClass(); onStartFocus() }, enabled = !state.focusRunning, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Empezar clase")
                        }
                        if (state.focusRunning) OutlinedButton(onClick = onStopFocus) { Icon(Icons.Rounded.Stop, "Detener") }
                    }
                    if (!state.dndEnabledByApp) {
                        TextButton(onClick = onNeedDndAccess) { Text("Permitir No molestar para silenciar interrupciones") }
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Abrir rápido", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                ActionRow(Icons.Rounded.AutoAwesome, "ChatGPT", "Preguntas, resúmenes y explicaciones", onClick = { ExternalApps.openChatGpt(context) })
                ActionRow(Icons.Rounded.Language, "Brave", "Campus, Moodle, búsquedas y archivos", onClick = { ExternalApps.openBrave(context) })
                ActionRow(Icons.Rounded.Description, "Word / Microsoft 365", "Abrir o editar documentos", onClick = { ExternalApps.openWord(context) })
                ActionRow(Icons.Rounded.FolderOpen, "Abrir archivo", "Word, PDF, PowerPoint o texto", onClick = {
                    openDocument.launch(arrayOf(
                        "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/plain"
                    ))
                })
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Notas rápidas", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Se guardan automáticamente en el teléfono.", color = A53Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.quickNote,
                    onValueChange = onNoteChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                    placeholder = { Text("Idea, tarea, duda para preguntar después…") }
                )
            }
        }
        if (state.recentDocuments.isNotEmpty()) {
            item { Text("Documentos recientes", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            items(state.recentDocuments, key = { it.uri.toString() }) { doc ->
                Card(onClick = { ExternalApps.openDocument(context, doc.uri) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp)) {
                        Icon(Icons.Rounded.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(doc.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("Tocar para abrir", color = A53Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { ExternalApps.openDocument(context, doc.uri, forceBrave = true) }) { Icon(Icons.Rounded.Language, "Abrir con Brave") }
                    }
                }
            }
        }
    }
}

private fun formatTimer(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
private fun queryName(context: Context, uri: Uri): String {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else "Documento"
    } ?: "Documento"
}
