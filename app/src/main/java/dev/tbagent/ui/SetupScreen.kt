package dev.tbagent.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.tbagent.Graph
import dev.tbagent.env.EnvironmentInspector
import dev.tbagent.env.SetupStatus
import kotlinx.coroutines.delay

/**
 * Android does not allow an app to grant itself these settings with a popup (accessibility service,
 * default TTS engine, restricted settings). This screen shows what is missing and opens the right page.
 * No runtime permission is needed: voice input uses the system speech dialog.
 */
@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf<SetupStatus?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            s = EnvironmentInspector.status(ctx)
            delay(1500)
        }
    }
    val st = s ?: return
    val required = listOf(st.ourEngineSelected, st.controllerEnabled && st.controllerConnected, st.talkBackEnabled, st.chromeInstalled)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Section(if (required.all { it }) "Setup complete" else "Setup: ${required.count { it }} of ${required.size} done") {
            Text(
                "Android does not let an app grant these itself, so each item opens its settings page. " +
                    "This screen updates automatically when you come back.",
            )
        }
        Check(
            "1. Select \"TalkBack Capture (TTS)\" as the preferred TTS engine",
            st.ourEngineSelected,
            "Current: ${st.defaultEngine ?: "unknown"}. Settings → General management → Language → Text-to-speech → Preferred engine.",
        ) {
            OutlinedButton(onClick = { openTtsSettings(ctx) }) { Text("Open text-to-speech settings") }
        }
        Check(
            "2. Enable the \"TalkBack Agent Controller\" accessibility service",
            st.controllerEnabled && st.controllerConnected,
            if (st.controllerEnabled && !st.controllerConnected) "Enabled but not connected yet; wait a few seconds or toggle it off and on."
            else "Settings → Accessibility → Installed apps → TalkBack Agent Controller. If it says \"Restricted setting\": " +
                "open App info → ⋮ (top right) → Allow restricted settings, then try again.",
        ) {
            OutlinedButton(onClick = { open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open accessibility settings") }
            OutlinedButton(onClick = {
                open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Open app info (allow restricted settings)") }
        }
        Check(
            "3. Turn TalkBack on",
            st.talkBackEnabled,
            "Package: ${st.talkBackVersion ?: "not found"}. Touch exploration: ${st.touchExploration}. " +
                "Tip: set the volume-keys shortcut to switch TalkBack on and off quickly.",
        ) {
            OutlinedButton(onClick = { open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open accessibility settings") }
        }
        Check("4. Chrome installed", st.chromeInstalled, st.chromeVersion ?: "Install Google Chrome from the Play Store.") {}

        Section("Voice input") {
            Text("No permission is needed: the microphone button on the Run tab opens the system speech dialog.")
        }
        AiSection()

        Section("When you finish testing") {
            Text(
                "Select the Samsung or Google engine as the preferred TTS engine again. While \"TalkBack Capture\" is " +
                    "selected, the phone's voice depends on this app's audible echo.",
            )
        }
        Section("Environment") {
            EnvironmentInspector.environmentMap(ctx).forEach { (k, v) -> KeyValue(k, v) }
        }
    }
}

@Composable
private fun Check(title: String, ok: Boolean, detail: String, actions: @Composable () -> Unit) {
    Section(title) {
        Text(if (ok) "✓ Done" else "✗ Pending", color = if (ok) Ok else Bad, fontWeight = FontWeight.Bold)
        Text(detail)
        actions()
    }
}

@Composable
private fun AiSection() {
    var key by remember { mutableStateOf(Graph.settings.openAiKey) }
    var model by remember { mutableStateOf(Graph.settings.openAiModel) }
    var use by remember { mutableStateOf(Graph.settings.useAi) }
    Section("AI (OpenAI): interpret instructions and evaluate results") {
        Text(
            "The key is typed here, stays only on this phone and is never uploaded to the repository. " +
                "Set a spending limit in your OpenAI account.",
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; Graph.settings.openAiKey = it },
            label = { Text("OpenAI API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; Graph.settings.openAiModel = it },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = use, onCheckedChange = { use = it; Graph.settings.useAi = it })
            Text("  Use AI on Run (interpret the request and evaluate the result)")
        }
    }
}

private fun openTtsSettings(ctx: Context) {
    if (!open(ctx, Intent("com.android.settings.TTS_SETTINGS"))) open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun open(ctx: Context, intent: Intent): Boolean = try {
    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
