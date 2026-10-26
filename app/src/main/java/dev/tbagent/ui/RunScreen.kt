package dev.tbagent.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tbagent.Graph
import dev.tbagent.ai.EvaluationOutcome
import dev.tbagent.ai.OpenAiEvaluator
import dev.tbagent.ai.OpenAiInterpreter
import dev.tbagent.core.CaptureStatus
import dev.tbagent.core.Interpretation
import dev.tbagent.core.ResultAssembler
import dev.tbagent.core.RunResult
import dev.tbagent.core.StrategyResult
import dev.tbagent.core.Verdict
import dev.tbagent.data.JsonCodec
import dev.tbagent.orchestrator.RunState
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun RunScreen() {
    val ctx = LocalContext.current
    var url by rememberSaveable { mutableStateOf(Graph.settings.lastUrl) }
    var instruction by rememberSaveable { mutableStateOf(Graph.settings.lastInstruction) }
    var micLanguage by remember { mutableStateOf(Graph.settings.micLanguage) }
    var micMessage by remember { mutableStateOf<String?>(null) }
    val state by Graph.orchestrator.state.collectAsState()
    val log by Graph.orchestrator.log.collectAsState()
    val interpretation = remember(url, instruction) { Graph.interpreter.interpret(instruction, url) }
    val running = state is RunState.Running
    val scope = rememberCoroutineScope()
    var aiBusy by remember { mutableStateOf(false) }
    var aiMessage by remember { mutableStateOf<String?>(null) }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) instruction = if (instruction.isBlank()) heard else "${instruction.trimEnd()} $heard"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = instruction,
            onValueChange = { instruction = it },
            label = { Text("What do you want to validate?") },
            placeholder = { Text("Find Primary Address and tell me exactly what TalkBack announces.") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {
                micMessage = null
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, micLanguage)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what you want to validate")
                try {
                    speech.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    micMessage = "No speech recognition app is available on this phone."
                }
            }) { Text("🎤 Dictate") }
            FilterChip(selected = micLanguage == "en-US", onClick = { micLanguage = "en-US"; Graph.settings.micLanguage = "en-US" }, label = { Text("EN") })
            FilterChip(selected = micLanguage == "es-AR", onClick = { micLanguage = "es-AR"; Graph.settings.micLanguage = "es-AR" }, label = { Text("ES") })
        }
        micMessage?.let { Text(it, color = Warn, style = MaterialTheme.typography.bodySmall) }

        if (!Graph.settings.useAi) {
            when (val i = interpretation) {
                is Interpretation.Ok -> {
                    val t = i.spec.target
                    Text(
                        "Element: \"${t.name}\"" + (t.role?.let { " · role $it" } ?: "") +
                            " · strategies: ${i.spec.strategies.joinToString()}" +
                            (i.spec.expectation?.let { " · expected (${it.mode}): \"${it.text}\"" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                is Interpretation.NeedsClarification -> if (instruction.isNotBlank()) {
                    Text(i.message, color = Warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        } else {
            Text(
                "AI is on: your request is interpreted by OpenAI when you tap Run.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !running && !aiBusy &&
                    (interpretation is Interpretation.Ok || (Graph.settings.useAi && instruction.isNotBlank())),
                onClick = {
                    Graph.settings.lastUrl = url
                    Graph.settings.lastInstruction = instruction
                    aiMessage = null
                    val s = Graph.settings
                    if (s.useAi && s.openAiKey.isNotBlank()) {
                        aiBusy = true
                        scope.launch {
                            val r = OpenAiInterpreter(s.openAiKey, s.openAiModel).interpret(instruction, url)
                            aiBusy = false
                            when (r) {
                                is Interpretation.Ok -> Graph.orchestrator.start(r.spec)
                                is Interpretation.NeedsClarification -> aiMessage = r.message
                            }
                        }
                    } else {
                        if (s.useAi) aiMessage = "The OpenAI key is missing (Setup tab). Using the rule-based interpreter."
                        (interpretation as? Interpretation.Ok)?.let { Graph.orchestrator.start(it.spec) }
                    }
                },
            ) { Text(if (aiBusy) "Interpreting…" else "Run") }
            if (running) OutlinedButton(onClick = { Graph.orchestrator.cancel() }) { Text("Cancel") }
        }

        aiMessage?.let { Text(it, color = Warn, modifier = Modifier.padding(bottom = 6.dp)) }
        when (val s = state) {
            RunState.Idle -> Text(
                "Requirements: Setup complete (TTS engine + service + TalkBack). Run opens Chrome; " +
                    "when the run ends the app comes back by itself (if Android allows it), otherwise return manually.",
                style = MaterialTheme.typography.bodySmall,
            )
            is RunState.Running -> Text("Running: ${s.step}")
            is RunState.Failed -> Section("The run could not be completed") { Text(s.message, color = Bad) }
            is RunState.NeedsSelection -> Section("Several elements match: pick one") {
                s.candidates.forEach { c ->
                    OutlinedButton(
                        onClick = { Graph.orchestrator.start(s.spec.copy(candidateKey = c.key)) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text("${c.role} · \"${c.accessibleName}\" · ${c.node.bounds}")
                    }
                }
            }
            is RunState.Finished -> ResultView(s.result, s.file)
        }

        if (log.isNotEmpty()) {
            Section("Run log") { log.forEach { Mono(it) } }
        }
    }
}

@Composable
private fun ResultView(r: RunResult, file: File) {
    val ctx = LocalContext.current
    Section("Result: ${r.verdict}") {
        Text(
            r.verdict.name,
            fontWeight = FontWeight.Bold,
            color = when (r.verdict) {
                Verdict.PASS, Verdict.OBSERVED -> Ok
                Verdict.FAIL -> Bad
                Verdict.INCONCLUSIVE -> Warn
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        r.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        KeyValue("Requested URL", r.url)
        KeyValue("URL in Chrome", r.observedPageUrl ?: "not read")
        r.expectation?.let { KeyValue("Expected (${it.mode})", it.text) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { shareFile(ctx, "talkback-run-${r.id}.json", file.readText()) }) { Text("Share JSON") }
            OutlinedButton(onClick = { copyToClipboard(ctx, "run ${r.id}", file.readText()) }) { Text("Copy JSON") }
        }
    }
    AiEvaluationSection(r)
    r.selected?.let { c ->
        Section("Element found") {
            KeyValue("Accessible name (node property, not an announcement)", c.accessibleName)
            KeyValue("Inferred role", c.role.name)
            KeyValue("Class", c.node.className ?: "?")
            KeyValue("Match", "${c.matchedField} · score ${"%.2f".format(c.score)}")
            KeyValue("Bounds", c.node.bounds.toString())
        }
    }
    r.strategies.forEach { StrategyView(it, r) }
    Section("Environment") { r.environment.forEach { (k, v) -> KeyValue(k, v) } }
}

@Composable
private fun AiEvaluationSection(r: RunResult) {
    val settings = Graph.settings
    if (!settings.useAi || settings.openAiKey.isBlank()) return
    var outcome by remember(r.id) { mutableStateOf<EvaluationOutcome?>(null) }
    var busy by remember(r.id) { mutableStateOf(true) }
    var attempt by remember(r.id) { mutableStateOf(0) }
    LaunchedEffect(r.id, attempt) {
        busy = true
        outcome = OpenAiEvaluator(settings.openAiKey, settings.openAiModel).evaluate(r)
        busy = false
    }
    Section("AI evaluation of your request (OpenAI opinion, not a measurement)") {
        when {
            busy -> Text("Evaluating the evidence…")
            outcome is EvaluationOutcome.Error -> Text((outcome as EvaluationOutcome.Error).message, color = Bad)
            outcome is EvaluationOutcome.Done -> {
                val e = (outcome as EvaluationOutcome.Done).evaluation
                Text(
                    "Answered: ${e.answered}",
                    fontWeight = FontWeight.Bold,
                    color = when (e.answered) {
                        "YES" -> Ok
                        "NO" -> Bad
                        else -> Warn
                    },
                )
                Text(e.summary)
                e.findings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                e.caveats.forEach { Text("⚠ $it", color = Warn, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (!busy) OutlinedButton(onClick = { attempt++ }) { Text("Evaluate again") }
    }
}

@Composable
private fun StrategyView(s: StrategyResult, r: RunResult) {
    val ctx = LocalContext.current
    Section("${s.strategy}: ${s.status}") {
        Text(ResultAssembler.describe(s.status), style = MaterialTheme.typography.bodySmall)
        val label = if (s.status == CaptureStatus.CAPTURED) "Captured announcement (text TalkBack sent to the TTS engine)"
        else "Received text (NOT confirmed as the announcement of the element)"
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(if (s.concatenated.isEmpty()) "— nothing —" else "“${s.concatenated}”", style = MaterialTheme.typography.titleMedium)
        s.expectationMet?.let { Text(if (it) "Meets the expectation" else "Does not meet the expectation", color = if (it) Ok else Bad) }

        if (s.fragments.isNotEmpty()) {
            Text("Original fragments (${s.fragments.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            val from = s.steps.lastOrNull()?.actionAtElapsed
            s.fragments.forEach { UtteranceRow(it, ctx.packageName, from) }
        }
        if (s.steps.isNotEmpty()) {
            Text("Steps", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            s.steps.forEach { st ->
                Mono(
                    "${st.index}. ${st.action} · accepted=${st.actionAccepted} · focus on target=${st.focusOnTarget}\n" +
                        "   focus: ${st.focusedDescription ?: "none"}\n" +
                        "   TTS: " + st.utterances.joinToString(" | ") { "\"${it.text}\" [${callerLabel(it, ctx.packageName)}, ${statusLabel(it.status)}]" }
                            .ifEmpty { "—" },
                )
            }
        }
        if (s.focusEvents.isNotEmpty()) {
            Text("Accessibility events (${s.focusEvents.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            s.focusEvents.takeLast(20).forEach { Mono("${wallTime(it.atWallMillis)} ${it.type} ${it.text}") }
        }
        if (s.otherUtterances.isNotEmpty()) {
            Text("Other TTS texts in the window (not TalkBack): ${s.otherUtterances.size}", style = MaterialTheme.typography.bodySmall)
        }
        s.notes.forEach { Text("⚠ $it", color = Warn, style = MaterialTheme.typography.bodySmall) }
        s.screenshotPath?.let { path ->
            val bmp = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "Screenshot ${s.strategy} of ${r.id}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 6.dp),
                )
            }
        }
    }
}
