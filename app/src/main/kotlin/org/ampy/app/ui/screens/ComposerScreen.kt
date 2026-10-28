package org.ampy.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.components.Dropdown
import org.ampy.app.ui.components.LabeledSlider
import org.ampy.app.ui.components.SectionCard
import org.ampy.composerai.Character
import org.ampy.composerai.ComposeRequest
import org.ampy.composerai.Genre
import org.ampy.composerai.ReferenceStyle
import org.ampy.composerai.SectionType
import org.ampy.core.InstrumentKind
import org.ampy.core.KeySignature
import org.ampy.instrumentai.InstrumentClassifier

/** Editor di composizione: tutti i parametri richiesti (strumenti, durata, BPM, tonalità, struttura, complessità, carattere). */
@Composable
fun ComposerScreen(vm: AppViewModel, onClose: () -> Unit) {
    var genre by remember { mutableStateOf(Genre.ROCK) }
    var character by remember { mutableStateOf(Character.ENERGICO) }
    var autoBpm by remember { mutableStateOf(true) }
    var bpm by remember { mutableFloatStateOf(120f) }
    var tonic by remember { mutableStateOf(9) }
    var minor by remember { mutableStateOf(true) }
    var duration by remember { mutableFloatStateOf(240f) }
    var complexity by remember { mutableFloatStateOf(0.5f) }
    var seed by remember { mutableStateOf("1") }
    var title by remember { mutableStateOf("Nuova composizione") }
    val instruments = remember { mutableStateListOf<InstrumentKind>() }
    val structure = remember { mutableStateListOf<SectionType>() }
    var style by remember { mutableStateOf<ReferenceStyle?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AmpyComposer", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onClose) { Text("Chiudi") }
        }
        Text("Composizione gerarchica: struttura → armonia → motivi sviluppati → basso → batteria → arrangiamento. Algoritmica e deterministica (stesso seme = stesso brano), nessun modello generativo neurale.",
            style = MaterialTheme.typography.bodySmall)
        SectionCard("Stile") {
            OutlinedTextField(title, { title = it }, label = { Text("Titolo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Dropdown("Genere", Genre.entries, genre, { it.label }, { genre = it })
            Dropdown("Carattere", Character.entries, character, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { character = it })
            Row(verticalAlignment = Alignment.CenterVertically) { Text("BPM automatico", Modifier.weight(1f)); Switch(autoBpm, { autoBpm = it }) }
            if (!autoBpm) LabeledSlider("BPM", bpm, 40f..220f, { "%.0f".format(it) }, onChange = { bpm = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown("Tonica", (0..11).toList(), tonic, { KeySignature.PITCH_NAMES[it] }, { tonic = it })
                FilterChip(selected = !minor, onClick = { minor = false }, label = { Text("Maggiore") })
                FilterChip(selected = minor, onClick = { minor = true }, label = { Text("Minore") })
            }
            LabeledSlider("Durata", duration, 60f..600f, { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) }, onChange = { duration = it })
            LabeledSlider("Complessità", complexity, 0f..1f, { "%.0f%%".format(it * 100) }, onChange = { complexity = it })
            OutlinedTextField(seed, { seed = it.filter { ch -> ch.isDigit() }.take(9) }, label = { Text("Seme (variazioni)") }, singleLine = true)
        }
        SectionCard("Strumenti (vuoto = predefiniti del genere: ${genre.instruments.joinToString { InstrumentClassifier.displayName(it) }})") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InstrumentKind.entries.filter { it != InstrumentKind.OTHER }.forEach { k ->
                    FilterChip(selected = k in instruments, onClick = { if (k in instruments) instruments.remove(k) else instruments.add(k) }, label = { Text(InstrumentClassifier.displayName(k)) })
                }
            }
        }
        SectionCard("Struttura (vuota = pianificata automaticamente in base alla durata)") {
            Text(if (structure.isEmpty()) "Automatica" else structure.joinToString(" → ") { it.label }, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionType.entries.forEach { s -> AssistChip(onClick = { structure.add(s) }, label = { Text("+ ${s.label}") }) }
            }
            Row { TextButton(onClick = { if (structure.isNotEmpty()) structure.removeAt(structure.lastIndex) }) { Text("Rimuovi ultima") }; TextButton(onClick = { structure.clear() }) { Text("Svuota") } }
        }
        SectionCard("Brano di riferimento (facoltativo)") {
            Text("Analizza il progetto aperto e ne usa solo caratteristiche generali (tempo, modo, densità, uso delle settime). Melodie e arrangiamenti non vengono copiati.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { style = vm.referenceStyleFromCurrent() }) { Text("Analizza progetto corrente") }
            style?.let { Text("Riferimento: %.0f BPM, %s, %.1f note/s, settime %.0f%% → complessità %.0f%%".format(it.bpm, if (it.minor) "minore" else "maggiore", it.notesPerSecond, it.seventhRatio * 100, it.suggestedComplexity * 100), style = MaterialTheme.typography.bodySmall) }
        }
        Button(onClick = {
            var req = ComposeRequest(genre = genre, bpm = if (autoBpm) null else bpm.toDouble(), tonic = tonic, minor = minor, durationSec = duration.toDouble(),
                structure = structure.toList(), instruments = instruments.toList(), complexity = complexity.toDouble(), character = character,
                seed = seed.toLongOrNull() ?: 1L, title = title)
            style?.let { req = it.applyTo(req, keepKey = true) }
            vm.compose(req); onClose()
        }, Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("Componi (${"%d:%02d".format(duration.toInt() / 60, duration.toInt() % 60)})") }
    }
}
