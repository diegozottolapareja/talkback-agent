package dev.tbagent.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tbagent.Graph
import dev.tbagent.core.A11yEventRecord
import dev.tbagent.core.Utterance
import dev.tbagent.data.JsonCodec
import dev.tbagent.env.EnvironmentInspector
import dev.tbagent.env.SetupStatus
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private sealed interface TimelineItem {
    val at: Long
    data class Tts(val u: Utterance) : TimelineItem { override val at get() = u.receivedAtElapsed }
    data class Event(val e: A11yEventRecord) : TimelineItem { override val at get() = e.atElapsed }
}

/** Milestone 1: manual proof that TalkBack's speech reaches our engine. */
@Composable
fun DiagnosticsScreen() {
    val ctx = LocalContext.current
    val utterances by Graph.utterances.items.collectAsState()
    val events by Graph.events.items.collectAsState()
    var status by remember { mutableStateOf<SetupStatus?>(null) }
    var echo by remember { mutableStateOf(Graph.settings.echoEnabled) }
    var fast by remember { mutableStateOf(Graph.settings.fastMode) }
    var validated by remember { mutableStateOf(Graph.settings.captureValidated) }
    var showEvents by remember { mutableStateOf(true) }
    var selfTestMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            status = EnvironmentInspector.status(ctx)
            delay(1500)
        }
    }

    // Client bound explicitly to OUR engine, used for the self-test.
    var selfTts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val t = TextToSpeech(ctx, { s -> selfTestMessage = if (s == TextToSpeech.SUCCESS) null else "No se pudo conectar con el motor propio (código $s)." }, ctx.packageName)
        selfTts = t
        onDispose { t.shutdown() }
    }

    val timeline = remember(utterances, events, showEvents) {
        val items = utterances.map { TimelineItem.Tts(it) } +
            (if (showEvents) events.map { TimelineItem.Event(it) } else emptyList())
        items.sortedByDescending { it.at }.take(300)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Section("Milestone 1 — prueba manual del motor de captura") {
                Text(
                    "1) Setup: seleccioná \"TalkBack Capture (TTS)\" como motor preferido.\n" +
                        "2) Activá TalkBack.\n" +
                        "3) Abrí Chrome y navegá con swipes por una página.\n" +
                        "4) Volvé acá: cada texto que TalkBack mandó a sintetizar aparece abajo como TTS · TalkBack.\n" +
                        "5) Compará con lo que escuchaste (eco) o con TalkBack → Ajustes de desarrollador → \"Mostrar salida de voz\".",
                )
                val s = status
                if (s != null) {
                    KeyValue("Motor TTS preferido", (s.defaultEngine ?: "desconocido") + if (s.ourEngineSelected) " ✓" else " ✗ (no es el nuestro)")
                    KeyValue("TalkBack activo", if (s.talkBackEnabled) "sí (${s.talkBackPackage})" else "no")
                    KeyValue("Textos recibidos de TalkBack", s.talkBackUtterances.toString())
                    KeyValue("Eco audible por", s.echoEngine ?: "ningún motor encontrado")
                }
            }
        }
        item {
            Section("Opciones del motor") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = echo, onCheckedChange = { echo = it; Graph.settings.echoEnabled = it })
                    Text("  Eco audible (repite el texto con el motor de Samsung/Google)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = fast, onCheckedChange = { fast = it; Graph.settings.fastMode = it })
                    Text("  Modo rápido (sin audio simulado; no detecta interrupciones)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = validated, onCheckedChange = { validated = it; Graph.settings.captureValidated = it })
                    Text("  Validé que el texto capturado coincide con lo que dijo TalkBack")
                }
                Text(
                    "Sin esta validación, las ejecuciones automáticas devuelven INCONCLUSIVE aunque muestren el texto capturado.",
                )
            }
        }
        item {
            Section("Acciones") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val r = selfTts?.speak("Prueba del motor de captura", TextToSpeech.QUEUE_FLUSH, null, "selftest")
                        selfTestMessage = if (r == TextToSpeech.SUCCESS) "Enviado: debería aparecer como \"esta app\"." else "speak() devolvió $r"
                    }) { Text("Self-test") }
                    OutlinedButton(onClick = { shareText(ctx, "TalkBack capture log", exportLog(ctx.packageName)) }) { Text("Compartir log") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Graph.utterances.clear(); Graph.events.clear() }) { Text("Borrar") }
                    OutlinedButton(onClick = { showEvents = !showEvents }) {
                        Text(if (showEvents) "Ocultar eventos de foco" else "Mostrar eventos de foco")
                    }
                }
                selfTestMessage?.let { Text(it) }
            }
        }
        item { Text("Línea de tiempo (más reciente primero)", Modifier.padding(vertical = 8.dp)) }
        items(timeline, key = { if (it is TimelineItem.Tts) "t${it.u.id}" else "e${(it as TimelineItem.Event).e.id}" }) { item ->
            when (item) {
                is TimelineItem.Tts -> UtteranceRow(item.u, ctx.packageName)
                is TimelineItem.Event -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Mono("${wallTime(item.e.atWallMillis)} · ${item.e.type} · ${item.e.packageName ?: ""}")
                    if (item.e.text.isNotBlank()) Mono("   ${item.e.text}")
                }
            }
            HorizontalDivider()
        }
    }
}

private fun exportLog(ownPackage: String): String {
    val o = JSONObject()
        .put("environment", JSONObject(EnvironmentInspector.environmentMap(Graph.app) as Map<*, *>))
        .put("utterances", JSONArray(Graph.utterances.snapshot().map { JsonCodec.toJson(it) }))
        .put("accessibilityEvents", JSONArray(Graph.events.items.value.map { JsonCodec.toJson(it) }))
        .put("app", ownPackage)
    return o.toString(2)
}
