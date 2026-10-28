package org.ampy.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.components.Dropdown
import org.ampy.app.ui.components.Warning
import org.ampy.app.ui.theme.LocalStudioColors
import org.ampy.core.InstrumentKind
import org.ampy.core.Project
import org.ampy.core.Tuning
import org.ampy.scoreengine.TabGenerator

/** Tablatura per chitarra e basso con scelta dell'accordatura (la scelta modifica il progetto ed è annullabile). */
@Composable
fun TabScreen(vm: AppViewModel, project: Project) {
    val sel by vm.selectedTrackId.collectAsStateWithLifecycle()
    val candidates = project.tracks.filter { it.notes.isNotEmpty() && !it.instrument.isDrum }
    val track = candidates.firstOrNull { it.id == sel } ?: candidates.firstOrNull { it.instrument in setOf(InstrumentKind.GUITAR, InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.BASS) } ?: candidates.firstOrNull()
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(candidates) { t -> FilterChip(selected = t.id == track?.id, onClick = { vm.selectTrack(t.id) }, label = { Text(t.name) }) } }
        if (track == null) { Text("Nessuna traccia intonata con note."); return@Column }
        val tuning = track.tuning ?: if (track.instrument == InstrumentKind.BASS) Tuning.BASS_4 else Tuning.GUITAR_STANDARD
        Dropdown("Accordatura", Tuning.ALL, tuning, { it.name }, { vm.setTuning(track.id, it) })
        val text = remember(track.notes, tuning, project.initialBpm) {
            val g = TabGenerator(tuning); g.renderAscii(g.assign(track.notes), stepSec = 60.0 / project.initialBpm / 4)
        }
        if (track.instrument !in setOf(InstrumentKind.GUITAR, InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.BASS)) Warning("La traccia non è indicata come chitarra/basso: la tablatura è solo indicativa.")
        Text("Una colonna = 1/16 a ${"%.0f".format(project.initialBpm)} BPM; | = battuta. Il rigo TAB è incluso anche nello spartito e nell'export MusicXML.", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
        }
    }
}

/** Spettrogramma (scala logaritmica in frequenza) della traccia audio selezionata, primi 3 minuti. */
@Composable
fun SpectrogramScreen(vm: AppViewModel, project: Project) {
    val spec by vm.spectrogram.collectAsStateWithLifecycle()
    val sel by vm.selectedTrackId.collectAsStateWithLifecycle()
    val pos by vm.positionSec.collectAsStateWithLifecycle()
    val colors = LocalStudioColors.current
    val audioTracks = project.tracks.filter { it.audioFile != null }
    LaunchedEffect(sel) { if (spec?.first != sel) vm.computeSpectrogram() }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(audioTracks) { t -> FilterChip(selected = t.id == sel, onClick = { vm.selectTrack(t.id) }, label = { Text(t.name) }) } }
        val data = spec?.takeIf { it.first == sel }?.second
        if (audioTracks.isEmpty()) { Text("Nessuna traccia audio."); return@Column }
        if (data == null) { Text("Calcolo dello spettrogramma…"); return@Column }
        val bmp = remember(data) {
            val w = data.size; val h = data.firstOrNull()?.size ?: 1
            val px = IntArray(w * h)
            for (x in 0 until w) for (y in 0 until h) px[(h - 1 - y) * w + x] = colormap(data[x][y])
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        val durShown = data.size.toDouble() // frame
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            drawImage(bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height), dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
            val totalSec = minOf(project.durationSec, 180.0)
            if (totalSec > 0) { val x = (pos / totalSec * size.width).toFloat(); drawLine(colors.playhead, Offset(x, 0f), Offset(x, size.height), 2f) }
        }
        Text("Asse verticale logaritmico fino a 8 kHz; colore = energia (dB). ${durShown.toInt()} frame.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Mappa di colore tipo "magma" semplificata. */
private fun colormap(v: Float): Int {
    val t = v.coerceIn(0f, 1f)
    val r = (255 * minOf(1f, t * 1.8f)).toInt()
    val g = (255 * maxOf(0f, (t - 0.45f) * 1.8f).coerceAtMost(1f)).toInt()
    val b = (255 * (if (t < 0.5f) t * 1.4f else maxOf(0f, 1.4f - t * 1.6f) + maxOf(0f, (t - 0.85f) * 5f)).coerceIn(0f, 1f)).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
