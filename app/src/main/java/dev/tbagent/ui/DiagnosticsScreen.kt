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

/** Manual proof that TalkBack's speech reaches our engine. */
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
        val t = TextToSpeech(ctx, { s -> selfTestMessage = if (s == TextToSpeech.SUCCESS) null else "Could not connect to our own engine (code $s)." }, ctx.packageName)
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
            Section("Capture engine check") {
                Text(
                    "1) In Setup, select \"TalkBack Capture (TTS)\" as the preferred engine.\n" +
                        "2) Turn TalkBack on.\n" +
                        "3) Open Chrome and swipe through a page.\n" +
                        "4) Come back here: every text TalkBack sent to be synthesized shows up below as TalkBack.\n" +
                        "5) Compare with what you heard (echo) or with TalkBack → Developer settings → \"Display speech output\".",
                )
                val s = status
                if (s != null) {
                    KeyValue("Preferred TTS engine", (s.defaultEngine ?: "unknown") + if (s.ourEngineSelected) " ✓" else " ✗ (not ours)")
                    KeyValue("TalkBack on", if (s.talkBackEnabled) "yes (${s.talkBackPackage})" else "no")
                    KeyValue("Texts received from TalkBack", s.talkBackUtterances.toString())
                    KeyValue("Audible echo through", s.echoEngine ?: "no engine found")
                }
            }
        }
        item {
            Section("Engine options") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = echo, onCheckedChange = { echo = it; Graph.settings.echoEnabled = it })
                    Text("  Audible echo (repeats the text with the Samsung/Google engine)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = fast, onCheckedChange = { fast = it; Graph.settings.fastMode = it })
                    Text("  Fast mode (no simulated audio; cannot detect interruptions)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = validated, onCheckedChange = { validated = it; Graph.settings.captureValidated = it })
                    Text("  I checked that the captured text matches what TalkBack said")
                }
                Text("Until this is on, automatic runs report INCONCLUSIVE even though they show the captured text.")
            }
        }
        item {
            Section("Actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val r = selfTts?.speak("Capture engine self test", TextToSpeech.QUEUE_FLUSH, null, "selftest")
                        selfTestMessage = if (r == TextToSpeech.SUCCESS) "Sent: it should show up as \"this app\"." else "speak() returned $r"
                    }) { Text("Self-test") }
                    OutlinedButton(onClick = { Graph.utterances.clear(); Graph.events.clear() }) { Text("Clear") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = { shareFile(ctx, "talkback-capture-log.json", exportLog(ctx.packageName)) }) { Text("Share log") }
                    OutlinedButton(onClick = { copyToClipboard(ctx, "capture log", exportLog(ctx.packageName)) }) { Text("Copy log") }
                }
                OutlinedButton(onClick = { showEvents = !showEvents }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (showEvents) "Hide focus events" else "Show focus events")
                }
                selfTestMessage?.let { Text(it) }
            }
        }
        item { Text("Timeline (newest first)", Modifier.padding(vertical = 8.dp)) }
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
