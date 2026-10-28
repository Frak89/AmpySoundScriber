package org.ampy.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.theme.LocalStudioColors
import org.ampy.core.NoteEvent
import org.ampy.core.Project
import kotlin.math.roundToInt

private val NAMES = listOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")

/**
 * Editor piano roll: tocco = seleziona (o aggiunge in modalità "Aggiungi"), trascinamento della nota = sposta
 * in tempo e altezza (agganciato alla griglia), trascinamento a vuoto = scorre. Opacità = confidenza.
 */
@Composable
fun PianoRollScreen(vm: AppViewModel, project: Project) {
    val sel by vm.selectedTrackId.collectAsStateWithLifecycle()
    val selNote by vm.selectedNoteId.collectAsStateWithLifecycle()
    val pos by vm.positionSec.collectAsStateWithLifecycle()
    val colors = LocalStudioColors.current
    val tm = rememberTextMeasurer()
    val track = project.tracks.firstOrNull { it.id == sel } ?: project.tracks.firstOrNull { it.notes.isNotEmpty() }
    var pxPerSec by remember { mutableFloatStateOf(120f) }
    var rowH by remember { mutableFloatStateOf(14f) }
    var scrollSec by remember { mutableFloatStateOf(0f) }
    var topPitch by remember(track?.id) { mutableFloatStateOf(((track?.notes?.maxOfOrNull { it.pitch } ?: 72) + 4).toFloat()) }
    var addMode by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(4) } // suddivisioni per quarto
    // anteprima del trascinamento
    var dragDt by remember { mutableFloatStateOf(0f) }
    var dragDp by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf<Long?>(null) }
    val notesState = rememberUpdatedState(track?.notes ?: emptyList())
    val bpm = project.initialBpm
    val gridSec = 60.0 / bpm / grid

    Column(Modifier.fillMaxSize()) {
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(project.tracks.filter { it.notes.isNotEmpty() || it.audioFile == null }) { t -> FilterChip(selected = t.id == track?.id, onClick = { vm.selectTrack(t.id) }, label = { Text(t.name) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = addMode, onClick = { addMode = !addMode }, label = { Text("+ Aggiungi") })
            TextButton(onClick = vm::deleteSelectedNote, enabled = selNote != null) { Text("Elimina") }
            TextButton(onClick = { vm.velocity(+10) }, enabled = selNote != null) { Text("Vel+") }
            TextButton(onClick = { vm.velocity(-10) }, enabled = selNote != null) { Text("Vel−") }
            TextButton(onClick = { val t = track ?: return@TextButton; val n = t.notes.firstOrNull { it.id == selNote } ?: return@TextButton; vm.resizeNote(t.id, n.id, n.endSec + gridSec) }, enabled = selNote != null) { Text("Dur+") }
            TextButton(onClick = { val t = track ?: return@TextButton; val n = t.notes.firstOrNull { it.id == selNote } ?: return@TextButton; vm.resizeNote(t.id, n.id, n.endSec - gridSec) }, enabled = selNote != null) { Text("Dur−") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Griglia:", style = MaterialTheme.typography.bodySmall)
            listOf(1 to "1/4", 2 to "1/8", 3 to "terz.", 4 to "1/16").forEach { (g, l) -> TextButton(onClick = { grid = g }) { Text(if (grid == g) "[$l]" else l) } }
            OutlinedButton(onClick = { vm.quantizeTrack(grid) }) { Text("Quantizza") }
            TextButton(onClick = { pxPerSec = (pxPerSec * 1.4f).coerceAtMost(800f) }) { Text("Zoom+") }
            TextButton(onClick = { pxPerSec = (pxPerSec / 1.4f).coerceAtLeast(10f) }) { Text("Zoom−") }
        }
        if (track == null) { Text("Nessuna traccia con note: trascrivi un brano o aggiungi note.", Modifier.padding(16.dp)); return@Column }
        val selected = track.notes.firstOrNull { it.id == selNote }
        Text(selected?.let { "Selezionata: ${NAMES[it.pitch % 12]}${it.pitch / 12 - 1} · inizio %.3f s · durata %.3f s · velocity %d · confidenza %.0f%%".format(it.startSec, it.durationSec, it.velocity, it.confidence * 100) }
            ?: "${track.notes.size} note · tocca una nota per selezionarla", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))

        fun noteAt(o: Offset, notes: List<NoteEvent>): NoteEvent? {
            val t = scrollSec + o.x / pxPerSec; val p = (topPitch - o.y / rowH).toInt() + 1
            return notes.lastOrNull { it.pitch == p && t >= it.startSec && t <= it.endSec }
        }

        Canvas(Modifier.weight(1f).fillMaxWidth().background(colors.waveformBg)
            .pointerInput(track.id, addMode, grid) {
                detectTapGestures(
                    onTap = { o ->
                        val n = noteAt(o, notesState.value)
                        if (n != null) vm.selectedNoteId.value = n.id
                        else if (addMode) {
                            val t = ((scrollSec + o.x / pxPerSec) / gridSec).toInt() * gridSec
                            vm.addNote(track.id, (topPitch - o.y / rowH).toInt() + 1, t, gridSec * grid)
                        } else { vm.selectedNoteId.value = null; vm.seek(scrollSec + o.x / pxPerSec.toDouble()) }
                    },
                    onDoubleTap = { o -> noteAt(o, notesState.value)?.let { vm.selectedNoteId.value = it.id; vm.deleteSelectedNote() } },
                )
            }
            .pointerInput(track.id, grid) {
                detectDragGestures(
                    onDragStart = { o -> val n = noteAt(o, notesState.value); dragging = n?.id; if (n != null) vm.selectedNoteId.value = n.id; dragDt = 0f; dragDp = 0f },
                    onDragEnd = {
                        val id = dragging
                        if (id != null) {
                            val dt = (dragDt / gridSec).roundToInt() * gridSec; val dp = dragDp.roundToInt()
                            if (dt != 0.0 || dp != 0) vm.moveNote(track.id, id, dt, dp)
                        }
                        dragging = null; dragDt = 0f; dragDp = 0f
                    },
                    onDragCancel = { dragging = null; dragDt = 0f; dragDp = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        if (dragging != null) { dragDt += amount.x / pxPerSec; dragDp -= amount.y / rowH }
                        else { scrollSec = (scrollSec - amount.x / pxPerSec).coerceAtLeast(0f); topPitch = (topPitch + amount.y / rowH).coerceIn(12f, 127f) }
                    },
                )
            }) {
            val rows = (size.height / rowH).toInt() + 1
            // tasti e righe
            for (r in 0..rows) {
                val pitch = topPitch.toInt() - r
                if (pitch < 0) break
                val y = (topPitch - pitch) * rowH - (topPitch - topPitch.toInt()) * rowH
                val black = (pitch % 12) in setOf(1, 3, 6, 8, 10)
                drawRect(if (black) Color.Black.copy(alpha = 0.18f) else Color.Transparent, Offset(0f, y), Size(size.width, rowH))
                if (pitch % 12 == 0) {
                    drawLine(colors.gridStrong, Offset(0f, y + rowH), Offset(size.width, y + rowH))
                    drawText(tm, "Do${pitch / 12 - 1}", Offset(2f, y), TextStyle(color = colors.note, fontSize = 9.sp))
                }
            }
            var g = (scrollSec / gridSec).toInt() * gridSec
            var k = (scrollSec / gridSec).toInt()
            while ((g - scrollSec) * pxPerSec < size.width) {
                val x = ((g - scrollSec) * pxPerSec).toFloat()
                drawLine(if (k % (grid * project.timeSignature.numerator) == 0) colors.gridStrong else colors.grid, Offset(x, 0f), Offset(x, size.height))
                g += gridSec; k++
            }
            for (n in track.notes) {
                val isDrag = n.id == dragging
                val st = n.startSec + if (isDrag) (dragDt / gridSec).roundToInt() * gridSec else 0.0
                val p = n.pitch + if (isDrag) dragDp.roundToInt() else 0
                val x = ((st - scrollSec) * pxPerSec).toFloat()
                val w = (n.durationSec * pxPerSec).toFloat()
                if (x > size.width || x + w < 0) continue
                val y = (topPitch - p) * rowH
                val base = if (n.userEdited) colors.noteSelected.copy(alpha = 0.8f) else colors.note
                val alpha = 0.35f + 0.65f * n.confidence.coerceIn(0f, 1f)
                drawRect(base.copy(alpha = alpha), Offset(x, y + 1), Size(maxOf(3f, w), rowH - 2))
                if (n.confidence < 0.4f) drawRect(colors.noteLowConfidence, Offset(x, y + 1), Size(maxOf(3f, w), rowH - 2), style = Stroke(1.5f))
                if (n.id == selNote) drawRect(Color.White, Offset(x, y), Size(maxOf(3f, w), rowH), style = Stroke(2.5f))
            }
            val px = ((pos - scrollSec) * pxPerSec).toFloat()
            drawLine(colors.playhead, Offset(px, 0f), Offset(px, size.height), 2f)
        }
    }
}
