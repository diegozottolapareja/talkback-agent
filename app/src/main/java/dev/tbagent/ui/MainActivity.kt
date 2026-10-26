package dev.tbagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.tbagent.env.EnvironmentInspector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    // Open on Setup until the three required settings are done (they cannot be granted with a popup).
    val setupDone = remember {
        val s = EnvironmentInspector.status(ctx)
        s.ourEngineSelected && s.controllerEnabled && s.talkBackEnabled && s.chromeInstalled
    }
    var tab by rememberSaveable { mutableIntStateOf(if (setupDone) 0 else 2) }
    val titles = listOf("Run", "Diagnostics", "Setup")
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        // All text in the app can be selected and copied.
        SelectionContainer {
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> RunScreen()
                    1 -> DiagnosticsScreen()
                    else -> SetupScreen()
                }
            }
        }
    }
}
