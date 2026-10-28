package org.ampy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ampy.app.jobs.JobStatus
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.components.fmtTime
import org.ampy.core.Project

private val TABS = listOf("Elabora", "Timeline", "Piano roll", "Spartito", "Tablatura", "Spettrogramma", "Mixer", "Esporta", "Componi", "Modelli", "Apprendi", "Diagnostica", "Impostazioni")

@Composable
fun StudioScreen(vm: AppViewModel, project: Project) {
    var tab by rememberSaveable { mutableIntStateOf(if (project.tracks.any { it.notes.isNotEmpty() }) 1 else 0) }
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val pos by vm.positionSec.collectAsStateWithLifecycle()
    val labels by vm.historyLabels.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // barra superiore: titolo, annulla/ripristina, salva, chiudi
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(project.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = vm::undo, enabled = labels.first != null) { Text("↶") }
            TextButton(onClick = vm::redo, enabled = labels.second != null) { Text("↷") }
            TextButton(onClick = vm::save) { Text("Salva") }
            TextButton(onClick = vm::closeProject) { Text("Chiudi") }
        }
        // trasporto
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::togglePlay) { Text(if (playing) "❚❚ Pausa" else "▶ Play", fontWeight = FontWeight.Bold) }
            TextButton(onClick = vm::stopPlayback) { Text("■") }
            TextButton(onClick = { vm.seek(pos - 5) }) { Text("−5s") }
            TextButton(onClick = { vm.seek(pos + 5) }) { Text("+5s") }
            Text("${fmtTime(pos)} / ${fmtTime(project.durationSec)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp))
            Text("  ${"%.0f".format(project.initialBpm)} BPM · ${project.key.name} · ${project.timeSignature.numerator}/${project.timeSignature.denominator}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyRow(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(TABS) { i, t -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) }, modifier = Modifier.padding(start = if (i == 0) 8.dp else 0.dp)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when (TABS[tab]) {
                "Elabora" -> ProcessScreen(vm, project)
                "Timeline" -> TimelineScreen(vm, project)
                "Piano roll" -> PianoRollScreen(vm, project)
                "Spartito" -> ScoreScreen(vm, project)
                "Tablatura" -> TabScreen(vm, project)
                "Spettrogramma" -> SpectrogramScreen(vm, project)
                "Mixer" -> MixerScreen(vm, project)
                "Esporta" -> ExportScreen(vm, project)
                "Componi" -> ComposerScreen(vm, onClose = { tab = 1 })
                "Modelli" -> ModelsScreen(vm)
                "Apprendi" -> LearningScreen(vm, project)
                "Diagnostica" -> DiagnosticsScreen(vm)
                "Impostazioni" -> SettingsScreen(vm)
            }
        }
    }
}

/** Avanzamento reale dei job in corso, con annullamento. */
@Composable
fun JobsBar(vm: AppViewModel) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val visible = jobs.filter { it.status == JobStatus.RUNNING || (it.status == JobStatus.FAILED && System.currentTimeMillis() - (it.endedAtMs ?: 0) < 15000) }
    Column(Modifier.fillMaxWidth()) {
        for (j in visible.take(3)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (j.status == JobStatus.FAILED) "✗ ${j.title}: ${j.error}" else "${j.title} — ${j.message} (${"%.0f".format(j.progress * 100)}%)",
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, color = if (j.status == JobStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
                    if (j.status == JobStatus.RUNNING) LinearProgressIndicator(progress = { j.progress.toFloat() }, Modifier.fillMaxWidth())
                }
                if (j.status == JobStatus.RUNNING) TextButton(onClick = { vm.c.jobs.cancel(j.id) }, Modifier.width(96.dp)) { Text("Annulla") }
            }
        }
    }
}
