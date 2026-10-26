package dev.tbagent.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.tbagent.Graph
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tbagent.env.EnvironmentInspector
import dev.tbagent.env.SetupStatus
import kotlinx.coroutines.delay

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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Check(
            "1. Motor TTS \"TalkBack Capture (TTS)\" como preferido",
            st.ourEngineSelected,
            "Actual: ${st.defaultEngine ?: "desconocido"}. Ajustes → Accesibilidad → TalkBack → Configuración → " +
                "Texto a voz → Motor preferido (o Ajustes → Administración general → Salida de texto a voz).",
        ) {
            OutlinedButton(onClick = { openTtsSettings(ctx) }) { Text("Abrir ajustes de texto a voz") }
        }
        Check(
            "2. Servicio \"TalkBack Agent Controller\" activo",
            st.controllerEnabled && st.controllerConnected,
            if (st.controllerEnabled && !st.controllerConnected) "Habilitado pero no conectado todavía; esperá unos segundos o reactivalo."
            else "Ajustes → Accesibilidad → Apps instaladas → TalkBack Agent Controller. Si aparece \"Ajuste restringido\", " +
                "abrí Info de la app → ⋮ → \"Permitir ajustes restringidos\" y volvé a intentar.",
        ) {
            OutlinedButton(onClick = { open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Abrir ajustes de accesibilidad") }
            OutlinedButton(onClick = {
                open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Abrir info de la app") }
        }
        Check(
            "3. TalkBack activo",
            st.talkBackEnabled,
            "Paquete: ${st.talkBackVersion ?: "no encontrado"}. Exploración táctil: ${st.touchExploration}. " +
                "Tip: configurá el atajo de teclas de volumen para encender/apagar TalkBack rápido.",
        ) {
            OutlinedButton(onClick = { open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Abrir ajustes de accesibilidad") }
        }
        Check("4. Chrome instalado", st.chromeInstalled, st.chromeVersion ?: "Instalá Google Chrome desde Play Store.") {}

        AiSection()

        Section("Al terminar las pruebas") {
            Text(
                "Volvé a seleccionar el motor de Samsung o Google como preferido. Mientras \"TalkBack Capture\" esté " +
                    "seleccionado, la voz del teléfono depende del eco de esta app.",
            )
        }
        Section("Entorno") {
            EnvironmentInspector.environmentMap(ctx).forEach { (k, v) -> KeyValue(k, v) }
        }
    }
}

@Composable
private fun Check(title: String, ok: Boolean, detail: String, actions: @Composable () -> Unit) {
    Section(title) {
        Text(if (ok) "✓ Listo" else "✗ Pendiente", color = if (ok) Ok else Bad, fontWeight = FontWeight.Bold)
        Text(detail)
        actions()
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

@Composable
private fun AiSection() {
    var key by remember { mutableStateOf(Graph.settings.openAiKey) }
    var model by remember { mutableStateOf(Graph.settings.openAiModel) }
    var use by remember { mutableStateOf(Graph.settings.useAi) }
    Section("IA (OpenAI) para interpretar instrucciones") {
        Text(
            "La clave se escribe acá, queda solo en este teléfono y nunca se sube al repositorio. " +
                "Poné un límite de gasto en tu cuenta de OpenAI.",
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
            label = { Text("Modelo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = use, onCheckedChange = { use = it; Graph.settings.useAi = it })
            Text("  Usar IA al tocar Run")
        }
    }
}
