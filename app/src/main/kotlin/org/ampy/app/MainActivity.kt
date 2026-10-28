package org.ampy.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ampy.app.ui.AmpyRoot
import org.ampy.app.ui.AppViewModel
import org.ampy.app.ui.theme.AmpyTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            AmpyTheme(theme) { AmpyRoot(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** File condivisi/aperti da altre app (audio o MusicXML). */
    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { vm.importExternal(it, i.type) }
    }

    override fun onStop() {
        super.onStop()
        // Android 17: la riproduzione in background senza servizio dedicato viene bloccata; si mette in pausa
        // e si salva lo stato per poterlo recuperare.
        vm.onBackground()
    }
}
