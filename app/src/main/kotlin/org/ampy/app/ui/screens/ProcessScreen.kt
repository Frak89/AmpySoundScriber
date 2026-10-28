package org.ampy.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.components.SectionCard
import org.ampy.app.ui.components.Warning
import org.ampy.app.ui.components.fmtTime
import org.ampy.core.Project
import org.ampy.musicbrain.Chord
import org.ampy.musicbrain.ChordQuality
import org.ampy.transcriber.TranscriptionQuality

@Composable
fun ProcessScreen(vm: AppViewModel, project: Project) {
    val methods = remember(project.id) { vm.c.pipeline.separationMethods() }
    var advanced by remember { mutableStateOf(false) }
    val shown = methods.filter { m -> m.id == "dsp-hpss" || (if (advanced) m.stems.size > 4 else m.stems.size <= 4) }
    var method by remember { mutableStateOf(methods.first().id) }
    var quality by remember { mutableStateOf(vm.c.settings.quality) }
    val audioTracks = project.tracks.filter { it.audioFile != null }
    var selected by remember(project.tracks.map { it.id }) { mutableStateOf(audioTracks.filter { !it.muted }.map { it.id }.toSet()) }
    val hasAudio = project.sourceAudio != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        if (!hasAudio) Warning("Il progetto non contiene audio: separazione e trascrizione non sono applicabili (progetto MIDI/MusicXML o composizione).")
        project.notes.forEach { Warning(it) }

        SectionCard("1 · Separazione delle sorgenti") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !advanced, onClick = { advanced = false }, label = { Text("4 sorgenti") })
                FilterChip(selected = advanced, onClick = { advanced = true }, label = { Text("Multistrumento") })
            }
            shown.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = method == m.id, onClick = { method = m.id })
                    Column {
                        Text(m.label)
                        Text("Sorgenti: ${m.stems.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        Text(m.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            if (methods.size == 1) Text("Per voce/basso/batteria/altro installare un modello Demucs o Open-Unmix convertito in ONNX (scheda Modelli).", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.separate(method) }, enabled = hasAudio) { Text("Separa") }
        }

        SectionCard("2 · Trascrizione") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = quality == TranscriptionQuality.FAST, onClick = { quality = TranscriptionQuality.FAST }, label = { Text("Veloce") })
                FilterChip(selected = quality == TranscriptionQuality.QUALITY, onClick = { quality = TranscriptionQuality.QUALITY }, label = { Text("Massima qualità (+pitch bend)") })
            }
            Text(if (vm.c.pipeline.hasBasicPitch) "Motore: Basic Pitch (polifonico, ONNX) + batteria per bande + YIN di riserva" else "Motore: YIN monofonico (Basic Pitch non installato)",
                style = MaterialTheme.typography.bodySmall)
            audioTracks.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = t.id in selected, onCheckedChange = { c -> selected = if (c) selected + t.id else selected - t.id })
                    Text("${t.name} (${t.instrument.name.lowercase()})" + if (t.notes.isNotEmpty()) " · ${t.notes.size} note" else "")
                }
                t.warnings.forEach { Warning(it) }
            }
            Button(onClick = { vm.c.settings.quality = quality; vm.transcribe(quality, selected) }, enabled = selected.isNotEmpty()) { Text("Trascrivi tracce selezionate") }
        }

        SectionCard("3 · Analisi musicale") {
            FilledTonalButton(onClick = vm::analyze, enabled = project.tracks.any { it.notes.isNotEmpty() }) { Text("Analizza tempo, tonalità, accordi e struttura") }
            Text("Tempo: ${project.tempo.joinToString { "%.1f BPM da %s".format(it.bpm, fmtTime(it.timeSec)) }}")
            Text("Tonalità: ${project.key.name}")
            if (project.sections.isNotEmpty()) Text("Struttura: " + project.sections.joinToString(" | ") { "${it.label} ${fmtTime(it.startSec)}" }, style = MaterialTheme.typography.bodySmall)
            if (project.chords.isNotEmpty()) Text("Accordi: " + project.chords.take(48).joinToString("  ") { c ->
                Chord(c.root, runCatching { ChordQuality.valueOf(c.quality) }.getOrDefault(ChordQuality.MAJOR), c.bass).symbol + if (c.confidence < 0.3) "?" else ""
            }, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Proprietà del brano (modificabili, annullabili)") {
            var bpmText by remember(project.initialBpm) { mutableStateOf("%.1f".format(java.util.Locale.ROOT, project.initialBpm)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(bpmText, { bpmText = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("BPM iniziale") }, singleLine = true, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { bpmText.toDoubleOrNull()?.takeIf { it in 20.0..300.0 }?.let(vm::setTempo) ?: vm.toast("BPM non valido (20-300)") }) { Text("Applica") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                org.ampy.app.ui.components.Dropdown("Tonica", (0..11).toList(), project.key.tonicPitchClass, { org.ampy.core.KeySignature.PITCH_NAMES[it] }, { vm.setKey(it, project.key.isMinor) })
                FilterChip(selected = !project.key.isMinor, onClick = { vm.setKey(project.key.tonicPitchClass, false) }, label = { Text("Magg.") })
                FilterChip(selected = project.key.isMinor, onClick = { vm.setKey(project.key.tonicPitchClass, true) }, label = { Text("Min.") })
            }
            org.ampy.app.ui.components.Dropdown("Metro", listOf(2 to 4, 3 to 4, 4 to 4, 5 to 4, 6 to 8, 7 to 8, 12 to 8), project.timeSignature.numerator to project.timeSignature.denominator,
                { "${it.first}/${it.second}" }, { vm.setTimeSignature(it.first, it.second) })
        }

        SectionCard("Pipeline completa") {
            Text("Separazione → trascrizione di tutti gli stem → analisi, con avanzamento reale e possibilità di annullare. Le inferenze già completate vengono riprese dal checkpoint.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.fullPipeline(method, quality) }, enabled = hasAudio, modifier = Modifier.fillMaxWidth()) { Text("Esegui tutto") }
        }
    }
}
