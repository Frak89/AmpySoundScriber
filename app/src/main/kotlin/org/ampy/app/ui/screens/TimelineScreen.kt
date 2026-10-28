package org.ampy.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.theme.LocalStudioColors
import org.ampy.core.Project
import org.ampy.musicbrain.Chord
import org.ampy.musicbrain.ChordQuality

/**
 * Timeline multitraccia: per ogni traccia forma d'onda (se ha audio) e note in miniatura, sezioni e accordi
 * in testa, cursore di riproduzione. Tocco = posiziona la testina; pizzico = zoom; trascinamento = scorrimento.
 */
@Composable
fun TimelineScreen(vm: AppViewModel, project: Project) {
    val peaks by vm.peaks.collectAsStateWithLifecycle()
    val pos by vm.positionSec.collectAsStateWithLifecycle()
    val sel by vm.selectedTrackId.collectAsStateWithLifecycle()
    val colors = LocalStudioColors.current
    val tm = rememberTextMeasurer()
    var pxPerSec by remember { mutableFloatStateOf(40f) }
    var scrollSec by remember { mutableFloatStateOf(0f) }
    val duration = maxOf(project.durationSec, project.tracks.flatMap { it.notes }.maxOfOrNull { it.endSec } ?: 0.0, 1.0)

    Column(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxWidth().height(40.dp).background(colors.waveformBg)
            .pointerInput(Unit) { detectTapGestures { o -> vm.seek(scrollSec + o.x / pxPerSec.toDouble()) } }) {
            for (s in project.sections) {
                val x = ((s.startSec - scrollSec) * pxPerSec).toFloat()
                drawLine(colors.gridStrong, Offset(x, 0f), Offset(x, size.height), 2f)
                drawText(tm, s.label, Offset(x + 4, 0f), TextStyle(color = colors.playhead, fontSize = 11.sp))
            }
            for (ch in project.chords) {
                val x = ((ch.startSec - scrollSec) * pxPerSec).toFloat()
                if (x < -40 || x > size.width) continue
                val sym = Chord(ch.root, runCatching { ChordQuality.valueOf(ch.quality) }.getOrDefault(ChordQuality.MAJOR), ch.bass).symbol
                drawText(tm, sym, Offset(x + 2, size.height / 2), TextStyle(color = colors.note, fontSize = 11.sp))
            }
            val px = ((pos - scrollSec) * pxPerSec).toFloat()
            drawLine(colors.playhead, Offset(px, 0f), Offset(px, size.height), 3f)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            project.tracks.forEachIndexed { i, t ->
                val tc = colors.trackColors[i % colors.trackColors.size]
                Row(Modifier.fillMaxWidth().height(84.dp).clickable { vm.selectTrack(t.id) }
                    .background(if (t.id == sel) tc.copy(alpha = 0.12f) else Color.Transparent)) {
                    Column(Modifier.width(96.dp).padding(4.dp)) {
                        Text(t.name, style = MaterialTheme.typography.labelLarge, color = tc, maxLines = 2)
                        Text(buildString { if (t.muted) append("M "); if (t.solo) append("S "); append("${t.notes.size} note") }, style = MaterialTheme.typography.labelSmall)
                        if (t.warnings.isNotEmpty()) Text("⚠ ${t.warnings.size}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB300))
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().background(colors.waveformBg)
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    pxPerSec = (pxPerSec * zoom).coerceIn(2f, 600f)
                                    scrollSec = (scrollSec - pan.x / pxPerSec).coerceIn(0f, duration.toFloat())
                                }
                            }
                            .pointerInput(Unit) { detectTapGestures { o -> vm.seek(scrollSec + o.x / pxPerSec.toDouble()); vm.selectTrack(t.id) } }) {
                            val h = size.height; val mid = h / 2
                            // griglia ogni battuta
                            val barSec = 60.0 / project.initialBpm * project.timeSignature.numerator * 4 / project.timeSignature.denominator
                            var b = (scrollSec / barSec).toInt() * barSec
                            while ((b - scrollSec) * pxPerSec < size.width) { val x = ((b - scrollSec) * pxPerSec).toFloat(); drawLine(colors.grid, Offset(x, 0f), Offset(x, h)); b += barSec }
                            peaks[t.id]?.let { (mn, mx) ->
                                val secPerBucket = duration / mn.size
                                val first = (scrollSec / secPerBucket).toInt().coerceAtLeast(0)
                                var k = first
                                while (k < mn.size) {
                                    val x = ((k * secPerBucket - scrollSec) * pxPerSec).toFloat()
                                    if (x > size.width) break
                                    val w = maxOf(1f, (secPerBucket * pxPerSec).toFloat())
                                    drawRect(tc.copy(alpha = if (t.muted) 0.25f else 0.7f), Offset(x, mid - mx[k] * mid), Size(w, (mx[k] - mn[k]) * mid + 1))
                                    k++
                                }
                            }
                            if (t.notes.isNotEmpty()) {
                                val lo = t.notes.minOf { it.pitch }; val hi = maxOf(lo + 12, t.notes.maxOf { it.pitch })
                                for (n in t.notes) {
                                    val x = ((n.startSec - scrollSec) * pxPerSec).toFloat()
                                    if (x > size.width) continue
                                    val w = (n.durationSec * pxPerSec).toFloat()
                                    if (x + w < 0) continue
                                    val y = h - (n.pitch - lo + 1).toFloat() / (hi - lo + 2) * h
                                    drawRect(if (n.confidence < 0.4f) colors.noteLowConfidence else Color.White.copy(alpha = 0.85f), Offset(x, y - 2), Size(maxOf(2f, w), 4f))
                                }
                            }
                            val px = ((pos - scrollSec) * pxPerSec).toFloat()
                            drawLine(colors.playhead, Offset(px, 0f), Offset(px, h), 2f)
                        }
                    }
                }
            }
            Text("Pizzica per lo zoom, trascina per scorrere, tocca per posizionare la riproduzione. Le note rosse hanno confidenza bassa.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        }
    }
}
