package org.ampy.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ampy.app.pipeline.ExportFormat
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.components.Dropdown
import org.ampy.app.ui.components.LabeledSlider
import org.ampy.app.ui.components.SectionCard
import org.ampy.app.ui.components.Warning
import org.ampy.core.InstrumentKind
import org.ampy.core.Project
import org.ampy.core.Track
import org.ampy.instrumentai.InstrumentClassifier

/** Mixer: volume, pan, mute, solo per traccia (applicati in tempo reale alla riproduzione) e strumento assegnato. */
@Composable
fun MixerScreen(vm: AppViewModel, project: Project) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        project.tracks.forEach { t -> MixerStrip(vm, t) }
    }
}

@Composable
private fun MixerStrip(vm: AppViewModel, t: Track) {
    var vol by remember(t.id, t.volume) { mutableFloatStateOf(t.volume) }
    var pan by remember(t.id, t.pan) { mutableFloatStateOf(t.pan) }
    SectionCard(t.name) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = t.muted, onClick = { vm.setTrack(t.id, "Mute") { it.copy(muted = !it.muted) } }, label = { Text("Mute") })
            FilterChip(selected = t.solo, onClick = { vm.setTrack(t.id, "Solo") { it.copy(solo = !it.solo) } }, label = { Text("Solo") })
            Dropdown("Strumento", InstrumentKind.entries, t.instrument, { InstrumentClassifier.displayName(it) }, { vm.setInstrument(t.id, it) })
        }
        LabeledSlider("Volume", vol, 0f..1.5f, { "%.0f%%".format(it * 100) }, onChange = { vol = it }, onDone = { vm.setTrack(t.id, "Volume") { it.copy(volume = vol) } })
        LabeledSlider("Pan", pan, -1f..1f, { if (it < -0.05f) "S %.0f".format(-it * 100) else if (it > 0.05f) "D %.0f".format(it * 100) else "Centro" }, onChange = { pan = it }, onDone = { vm.setTrack(t.id, "Pan") { it.copy(pan = pan) } })
        Text(if (t.audioFile != null) "Sorgente: audio (${t.audioFile})" else "Sorgente: sintesi delle note (${t.notes.size})", style = MaterialTheme.typography.bodySmall)
        t.warnings.forEach { Warning(it) }
    }
}

/** Esportazioni individuali per traccia e complessive (MIDI, MusicXML, WAV, tablatura, PDF, progetto). */
@Composable
fun ExportScreen(vm: AppViewModel, project: Project) {
    var pending by remember { mutableStateOf<Pair<ExportFormat, String?>?>(null) }
    val launchers = ExportFormat.entries.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri -> val p = pending; if (uri != null && p != null) vm.export(p.first, p.second, uri); pending = null }
    }
    fun go(f: ExportFormat, trackId: String?, name: String) { pending = f to trackId; launchers[f]!!.launch("$name.${f.ext}") }
    val safe = project.title.replace(Regex("[^A-Za-z0-9 _-]"), "_")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        SectionCard("Brano completo") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { go(ExportFormat.MIDI, null, safe) }) { Text("MIDI multitraccia") }
                OutlinedButton(onClick = { go(ExportFormat.MUSICXML, null, safe) }) { Text("Partitura MusicXML") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { go(ExportFormat.WAV_RECONSTRUCTION, null, "$safe-ricostruzione") }) { Text("WAV ricostruzione") }
                OutlinedButton(onClick = { go(ExportFormat.TAB_TEXT, null, "$safe-tab") }) { Text("Tablature") }
            }
            OutlinedButton(onClick = { go(ExportFormat.PROJECT, null, safe) }) { Text("Progetto (.ampy.json)") }
            Text("PDF: scheda Spartito → «PDF» (stampa di sistema → Salva come PDF), per la partitura completa o per singola parte.", style = MaterialTheme.typography.bodySmall)
        }
        project.tracks.forEach { t ->
            val n = "$safe-${t.name.replace(Regex("[^A-Za-z0-9 _-]"), "_")}"
            SectionCard(t.name) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { go(ExportFormat.MIDI, t.id, n) }, enabled = t.notes.isNotEmpty()) { Text("MIDI") }
                    OutlinedButton(onClick = { go(ExportFormat.MUSICXML, t.id, n) }, enabled = t.notes.isNotEmpty()) { Text("MusicXML") }
                    OutlinedButton(onClick = { go(ExportFormat.WAV_RECONSTRUCTION, t.id, "$n-sintesi") }, enabled = t.notes.isNotEmpty()) { Text("WAV sintesi") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { go(ExportFormat.WAV_STEM, t.id, "$n-stem") }, enabled = t.audioFile != null) { Text("WAV stem") }
                    OutlinedButton(onClick = { go(ExportFormat.TAB_TEXT, t.id, "$n-tab") }, enabled = t.notes.isNotEmpty() && !t.instrument.isDrum) { Text("Tablatura") }
                }
            }
        }
    }
}
