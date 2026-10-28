package org.ampy.app.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.theme.LocalStudioColors
import org.ampy.core.AmpyLog
import org.ampy.core.LogCategory
import org.ampy.core.Project
import org.json.JSONObject

/**
 * Spartito renderizzato con Verovio (WASM, incluso negli asset: funziona offline).
 * Il cursore segue la riproduzione (evidenzia le note in suono); toccare una nota sposta la riproduzione.
 * "PDF" usa il servizio di stampa di Android (destinazione "Salva come PDF").
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScoreScreen(vm: AppViewModel, project: Project) {
    val ctx = LocalContext.current
    val colors = LocalStudioColors.current
    val pos by vm.positionSec.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    var partId by remember { mutableStateOf<String?>(null) } // null = partitura completa
    var status by remember { mutableStateOf("Caricamento…") }
    var ready by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(40) }
    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false // gli asset file:///android_asset restano accessibili
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            addJavascriptInterface(object {
                @JavascriptInterface fun onReady(version: String) { post { ready = true; status = "Verovio $version" } }
                @JavascriptInterface fun onLoaded(pages: Int) { post { status = "$pages pagine" } }
                @JavascriptInterface fun onError(msg: String) { AmpyLog.e(LogCategory.SYNC, "score", "Verovio: $msg"); post { status = "Errore: $msg" } }
                @JavascriptInterface fun onNoteTapped(id: String, timeMs: Double) { if (timeMs >= 0) post { vm.seek(timeMs / 1000.0) } }
            }, "AmpyBridge")
            loadUrl("file:///android_asset/score/score.html")
        }
    }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    val xml by produceXml(vm, project, partId)
    LaunchedEffect(xml, ready, scale) {
        val x = xml ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val opts = JSONObject().put("scale", scale).put("theme", JSONObject().put("fg", colors.scoreFg).put("bg", colors.scoreBg).put("accent", colors.scoreAccent))
        webView.evaluateJavascript("ampyLoad(${JSONObject.quote(x)}, $opts)", null)
    }
    // cursore sincronizzato (~15 aggiornamenti/s durante la riproduzione)
    LaunchedEffect(pos, playing) { if (ready && xml != null) webView.evaluateJavascript("ampySetTime(${(pos * 1000).toLong()})", null) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = partId == null, onClick = { partId = null }, label = { Text("Partitura completa") }) }
            items(project.tracks.filter { it.notes.isNotEmpty() }) { t -> FilterChip(selected = partId == t.id, onClick = { partId = t.id }, label = { Text(t.name) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { scale = (scale - 5).coerceAtLeast(20) }) { Text("A−") }
            TextButton(onClick = { scale = (scale + 5).coerceAtMost(80) }) { Text("A+") }
            TextButton(onClick = { printPdf(ctx, webView, project.title + (partId?.let { id -> " - " + (project.tracks.firstOrNull { it.id == id }?.name ?: "") } ?: "")) }) { Text("PDF") }
        }
        if (xml == null) Text("Nessuna nota da notare: trascrivi o componi un brano.", Modifier.padding(16.dp))
        AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun produceXml(vm: AppViewModel, project: Project, partId: String?) =
    androidx.compose.runtime.produceState<String?>(null, project, partId) { value = withContext(Dispatchers.Default) { vm.musicXmlFor(partId) } }

private fun printPdf(ctx: Context, wv: WebView, name: String) {
    // impaginazione A4 per la stampa: si ricarica la partitura con opzioni di pagina prima di stampare
    wv.evaluateJavascript("(function(){var x=document.querySelectorAll('.page svg').length;return x;})()") {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = wv.createPrintDocumentAdapter(name)
        pm.print(name, adapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }
}
