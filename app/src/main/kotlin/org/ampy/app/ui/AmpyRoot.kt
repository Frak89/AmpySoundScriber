package org.ampy.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ampy.app.ui.screens.HomeScreen
import org.ampy.app.ui.screens.JobsBar
import org.ampy.app.ui.screens.StudioScreen

@Composable
fun AmpyRoot(vm: AppViewModel) {
    val project by vm.project.collectAsStateWithLifecycle()
    val recoverable by vm.recoverable.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)) {
            JobsBar(vm)
            Box(Modifier.weight(1f)) {
                if (project == null) HomeScreen(vm) else StudioScreen(vm, project!!)
            }
        }
    }

    recoverable?.let {
        AlertDialog(
            onDismissRequest = { vm.acceptRecovery(false) },
            title = { Text("Salvataggio automatico trovato") },
            text = { Text("Esiste una versione più recente di \"${it.title}\" salvata automaticamente (probabile chiusura imprevista). Ripristinarla?") },
            confirmButton = { TextButton(onClick = { vm.acceptRecovery(true) }) { Text("Ripristina") } },
            dismissButton = { TextButton(onClick = { vm.acceptRecovery(false) }) { Text("Ignora") } },
        )
    }
}
