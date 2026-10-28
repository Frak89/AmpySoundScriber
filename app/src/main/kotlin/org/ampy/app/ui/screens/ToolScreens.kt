package org.ampy.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ampy.app.data.ThemeMode
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.components.Dropdown
import org.ampy.app.ui.components.LabeledSlider
import org.ampy.app.ui.components.SectionCard
import org.ampy.app.ui.components.Warning
import org.ampy.core.InferenceDevice
import org.ampy.core.LogCategory
import org.ampy.core.LogLevel
import org.ampy.core.Project
import org.ampy.diagnostics.LogAnalyzer
import org.ampy.modelmanager.ModelCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Gestore modelli
@Composable
fun ModelsScreen(vm: AppViewModel) {
    var refresh by remember { mutableIntStateOf(0) }
    val models = remember(refresh) { vm.c.models.list() }
    val bench by vm.benchmark.collectAsStateWithLifecycle()
    var manifestUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { m -> val mf = manifestUri; if (m != null && mf != null) { vm.installModel(mf, m); refresh++ }; manifestUri = null }
    val pickManifest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { manifestUri = u; pickModel.launch(arrayOf("application/octet-stream", "*/*")) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        SectionCard("Modelli installati") {
            if (models.isEmpty()) Text("Nessun modello: il modello Basic Pitch incluso viene installato al primo avvio.")
            models.forEach { m ->
                val ok = remember(m.manifest.id, refresh) { vm.c.models.verify(m) }
                val active = vm.c.models.active(m.manifest.task)?.manifest?.id == m.manifest.id
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = active, onClick = { vm.setActiveModel(m.manifest.task, m.manifest.id); refresh++ })
                    Column(Modifier.weight(1f)) {
                        Text(m.manifest.name)
                        Text("${m.manifest.task} · v${m.manifest.version} · ${m.manifest.license} · ${m.modelFile.length() / 1024} KB", style = MaterialTheme.typography.bodySmall)
                        Text(if (ok) "Integrità SHA-256 verificata" else "ATTENZIONE: file mancante o alterato", color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (!m.manifest.mobileVerified) Text("Prestazioni su smartphone non ancora certificate: usare il benchmark", style = MaterialTheme.typography.bodySmall)
                        if (m.manifest.notes.isNotEmpty()) Text(m.manifest.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.removeModel(m.manifest.id); refresh++ }) { Text("Rimuovi") }
                }
                HorizontalDivider()
            }
            OutlinedButton(onClick = { pickManifest.launch(arrayOf("application/json", "*/*")) }) { Text("Installa modello (manifest .json + file .onnx)") }
            Text("Formato del manifest: vedere docs/MODELS.md. I modelli di separazione Demucs/Open-Unmix vanno convertiti con gli script in tools/export.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Benchmark sul dispositivo (misura reale)") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(InferenceDevice.CPU, InferenceDevice.XNNPACK, InferenceDevice.NNAPI).forEach { d -> OutlinedButton(onClick = { vm.runBenchmark(d) }) { Text(d.name) } }
            }
            Text(bench ?: "Esegue 10 finestre di Basic Pitch e riporta i millisecondi misurati.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Progetti open source valutati") {
            ModelCatalog.entries.forEach { e ->
                Text("${e.name} — ${e.task}", style = MaterialTheme.typography.bodyMedium)
                Text("Licenza ${e.license}. ${e.status}. ${e.android}", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------- Apprendimento
@Composable
fun LearningScreen(vm: AppViewModel, project: Project) {
    val sel by vm.selectedTrackId.collectAsStateWithLifecycle()
    val tuning by vm.tuningResult.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val stats = remember(refresh) { vm.c.learning.errorStats() }
    val track = project.tracks.firstOrNull { it.id == sel }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Text("L'app migliora in modo verificabile adattando le soglie di decodifica alle tue correzioni (non riaddestra i pesi del modello sul telefono). Ogni adattamento riporta la F1 misurata prima e dopo.",
            style = MaterialTheme.typography.bodySmall)
        SectionCard("Traccia selezionata: ${track?.name ?: "nessuna"}") {
            Text("1. Correggi le note nel piano roll  2. Registra la correzione  3. Adatta i parametri", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { track?.let { vm.saveCorrections(it.id); refresh++ } }, enabled = track != null) { Text("Registra correzione") }
                OutlinedButton(onClick = { track?.let { vm.tuneOnTrack(it.id) } }, enabled = track?.notes?.any { it.userEdited } == true) { Text("Adatta parametri") }
            }
            tuning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        SectionCard("Statistiche degli errori (${stats.corrections} correzioni)") {
            Text("F1 media della trascrizione automatica rispetto alle correzioni: %.3f".format(stats.meanF1))
            Text("Note in più (falsi positivi): ${stats.falsePositives} · note mancanti: ${stats.misses}")
            Text("Errori d'ottava: ${stats.octaveErrors} · errori di semitono/tono: ${stats.semitoneErrors}")
            Text("Note mancanti nel registro grave: ${stats.lowRegisterMisses} · note brevi mancanti: ${stats.shortNoteMisses}")
            OutlinedButton(onClick = { val d = vm.c.learning.exportDataset("dataset-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())); vm.toast("Dataset creato in ${d.path}") }, enabled = stats.corrections > 0) { Text("Esporta dataset per fine-tuning (desktop)") }
            Text("Archivio: feedback, dataset, checkpoint e configurazioni in cartelle separate (${vm.c.learning.root.path}).", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- Diagnostica
@Composable
fun DiagnosticsScreen(vm: AppViewModel) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    var level by remember { mutableStateOf(LogLevel.INFO) }
    var cat by remember { mutableStateOf<LogCategory?>(null) }
    var pasted by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf<String?>(null) }
    var mem by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::exportDiagnostics) }
    val filtered = logs.filter { it.level.ordinal >= level.ordinal && (cat == null || it.category == cat) }.asReversed()
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Dropdown("Livello", LogLevel.entries, level, { it.name }, { level = it })
            Dropdown("Categoria", listOf<LogCategory?>(null) + LogCategory.entries, cat, { it?.name ?: "tutte" }, { cat = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { export.launch("ampy-diagnostica.txt") }) { Text("Esporta report") }
            OutlinedButton(onClick = { analysis = LogAnalyzer.analyze(logs.joinToString("\n") { vm.c.diagnostics.format(it) }).toText() }) { Text("Analizza") }
            OutlinedButton(onClick = { val (u, m) = vm.c.diagnostics.sampleMemory("ui"); mem = "RAM ${u / 1048576}/${m / 1048576} MB" }) { Text("Memoria") }
        }
        if (mem.isNotEmpty()) Text(mem, style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(pasted, { pasted = it }, label = { Text("Incolla un log esterno da analizzare") }, modifier = Modifier.fillMaxWidth().height(90.dp))
        if (pasted.isNotBlank()) TextButton(onClick = { analysis = LogAnalyzer.analyze(pasted).toText() }) { Text("Analizza log incollato") }
        analysis?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        LazyColumn(Modifier.weight(1f)) {
            items(filtered.take(500)) { r ->
                Text(vm.c.diagnostics.format(r).take(600), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = when (r.level) { LogLevel.ERROR -> MaterialTheme.colorScheme.error; LogLevel.WARN -> Color(0xFFFFB300); else -> MaterialTheme.colorScheme.onBackground })
            }
        }
    }
}

// ---------------------------------------------------------------- Impostazioni
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.c.settings
    val theme by vm.theme.collectAsStateWithLifecycle()
    var device by remember { mutableStateOf(s.device) }
    var seg by remember { mutableFloatStateOf(s.segmentSec) }
    var batch by remember { mutableFloatStateOf(s.batchSize.toFloat()) }
    var auto by remember { mutableFloatStateOf(s.autosaveSec.toFloat()) }
    var verbose by remember { mutableStateOf(s.verboseLog) }
    var license by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        SectionCard("Aspetto") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ThemeMode.entries.forEach { t -> FilterChip(selected = theme == t, onClick = { vm.setTheme(t) }, label = { Text(t.label) }) } }
        }
        SectionCard("Prestazioni") {
            Dropdown("Accelerazione", listOf(InferenceDevice.CPU, InferenceDevice.XNNPACK, InferenceDevice.NNAPI), device, { it.name }, { device = it; s.device = it })
            Text("XNNPACK = CPU ottimizzata ARM (consigliato su Pixel 7 Pro). NNAPI è deprecato da Android 15: se un operatore non è supportato si ricade su CPU (registrato nella diagnostica).", style = MaterialTheme.typography.bodySmall)
            LabeledSlider("Segmenti separazione", seg, 5f..60f, { "%.0f s".format(it) }, onChange = { seg = it }, onDone = { s.segmentSec = seg })
            LabeledSlider("Batch Basic Pitch", batch, 1f..8f, { "%.0f finestre".format(it) }, steps = 6, onChange = { batch = it }, onDone = { s.batchSize = batch.toInt() })
            LabeledSlider("Autosalvataggio", auto, 5f..120f, { "%.0f s".format(it) }, onChange = { auto = it }, onDone = { s.autosaveSec = auto.toInt() })
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Log dettagliati (DEBUG)", Modifier.weight(1f)); Switch(verbose, { verbose = it; s.verboseLog = it; org.ampy.core.AmpyLog.minLevel = if (it) LogLevel.DEBUG else LogLevel.INFO }) }
        }
        SectionCard("Licenze e componenti") {
            Text("AmpySoundScriber: Apache-2.0. Basic Pitch (Spotify): Apache-2.0. Verovio: LGPL-3.0 (file separato negli asset, sostituibile). ONNX Runtime: MIT.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("basic-pitch-LICENSE.txt", "basic-pitch-NOTICE.txt", "verovio-LGPL-3.0.txt").forEach { f ->
                    TextButton(onClick = { license = runCatching { ctx.assets.open("licenses/$f").bufferedReader().readText() }.getOrNull() }) { Text(f.substringBefore('.')) }
                }
            }
            license?.let { Text(it.take(4000), fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
        Warning("Nessuna funzione richiede connessione: modelli, notazione e sintesi funzionano offline.")
    }
}
