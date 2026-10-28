package org.ampy.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ampy.app.data.ThemeMode
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.components.SectionCard
import org.ampy.app.ui.components.fmtTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: AppViewModel) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val recording by vm.isRecording.collectAsStateWithLifecycle()
    val level by vm.recordLevel.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    var showComposer by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<String?>(null) }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importAudio) }
    val pickMidi = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importMidi) }
    val pickXml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importMusicXml) }
    val pickProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importProjectFile) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.startRecording() else vm.toast("Permesso microfono negato") }

    if (showComposer) { ComposerScreen(vm, onClose = { showComposer = false }); return }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("AmpySoundScriber", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Text("Ascolta → Separa → Riconosci → Trascrivi → Componi", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
        }
        item {
            SectionCard("Nuovo progetto") {
                Button(onClick = { pickAudio.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth()) { Text("Importa audio (MP3, WAV, FLAC, OGG, M4A, AAC)") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { pickMidi.launch(arrayOf("audio/midi", "audio/x-midi", "application/octet-stream")) }, Modifier.weight(1f)) { Text("Importa MIDI") }
                    FilledTonalButton(onClick = { pickXml.launch(arrayOf("application/vnd.recordare.musicxml+xml", "application/vnd.recordare.musicxml", "application/xml", "text/xml", "application/octet-stream")) }, Modifier.weight(1f)) { Text("Importa MusicXML") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!recording) OutlinedButton(onClick = { micPerm.launch(Manifest.permission.RECORD_AUDIO) }, Modifier.weight(1f)) { Text("● Registra dal microfono") }
                    else Button(onClick = vm::stopRecording, Modifier.weight(1f)) { Text("■ Ferma registrazione") }
                    OutlinedButton(onClick = { showComposer = true }, Modifier.weight(1f)) { Text("♫ Componi brano") }
                }
                if (recording) LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                TextButton(onClick = { pickProject.launch(arrayOf("application/json", "application/octet-stream")) }) { Text("Apri progetto .ampy.json") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tema:", Modifier.padding(end = 8.dp))
                ThemeMode.entries.forEach { t -> TextButton(onClick = { vm.setTheme(t) }) { Text(if (t == theme) "[${t.label}]" else t.label) } }
            }
            Text("Progetti", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            if (projects.isEmpty()) Text("Nessun progetto. Importa un brano o registra per iniziare.", style = MaterialTheme.typography.bodySmall)
        }
        items(projects, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().clickable { vm.openProject(p.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.title, fontWeight = FontWeight.SemiBold)
                    Text("${fmtTime(p.durationSec)} · ${p.tracks} tracce · ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(p.modifiedAtMs))}" + if (p.hasAutosave) " · autosalvataggio" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { toDelete = p.id }) { Text("Elimina") }
            }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    toDelete?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare il progetto?") },
            text = { Text("Il progetto, gli stem separati e le cache verranno rimossi definitivamente dal dispositivo.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(id); toDelete = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }
}
