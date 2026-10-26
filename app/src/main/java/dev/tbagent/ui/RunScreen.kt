package dev.tbagent.ui

import android.graphics.BitmapFactory
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.tbagent.ai.OpenAiInterpreter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.tbagent.core.CaptureStatus
import dev.tbagent.core.Interpretation
import dev.tbagent.core.ResultAssembler
import dev.tbagent.core.RunResult
import dev.tbagent.core.StrategyResult
import dev.tbagent.core.Verdict
import dev.tbagent.orchestrator.RunState
import java.io.File

@Composable
fun RunScreen() {
    val ctx = LocalContext.current
    var url by rememberSaveable { mutableStateOf(Graph.settings.lastUrl) }
    var instruction by rememberSaveable { mutableStateOf(Graph.settings.lastInstruction) }
    val state by Graph.orchestrator.state.collectAsState()
    val log by Graph.orchestrator.log.collectAsState()
    val interpretation = remember(url, instruction) { Graph.interpreter.interpret(instruction, url) }
    val running = state is RunState.Running
    val scope = rememberCoroutineScope()
    var aiBusy by remember { mutableStateOf(false) }
    var aiMessage by remember { mutableStateOf<String?>(null) }

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

        when (val i = interpretation) {
            is Interpretation.Ok -> {
                val t = i.spec.target
                Text(
                    "Elemento: \"${t.name}\"" + (t.role?.let { " · rol $it" } ?: "") +
                        " · estrategias: ${i.spec.strategies.joinToString()}" +
                        (i.spec.expectation?.let { " · esperado (${it.mode}): \"${it.text}\"" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            is Interpretation.NeedsClarification -> if (instruction.isNotBlank()) {
                Text(i.message, color = Warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
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
                        if (s.useAi) aiMessage = "Falta la clave de OpenAI (Setup). Uso el intérprete por reglas."
                        (interpretation as? Interpretation.Ok)?.let { Graph.orchestrator.start(it.spec) }
                    }
                },
            ) { Text(if (aiBusy) "Interpretando…" else "Run") }
            if (running) OutlinedButton(onClick = { Graph.orchestrator.cancel() }) { Text("Cancelar") }
        }

        aiMessage?.let { Text(it, color = Warn, modifier = Modifier.padding(bottom = 6.dp)) }
        when (val s = state) {
            RunState.Idle -> Text(
                "Requisitos: Setup completo (motor TTS + servicio + TalkBack). Al tocar Run se abre Chrome; " +
                    "al terminar la app vuelve sola (si Android lo permite) o volvé manualmente.",
                style = MaterialTheme.typography.bodySmall,
            )
            is RunState.Running -> Text("En curso: ${s.step}")
            is RunState.Failed -> Section("No se pudo completar") { Text(s.message, color = Bad) }
            is RunState.NeedsSelection -> Section("Varios elementos coinciden: elegí uno") {
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
            Section("Registro de ejecución") { log.forEach { Mono(it) } }
        }
    }
}

@Composable
private fun ResultView(r: RunResult, file: File) {
    val ctx = LocalContext.current
    Section("Resultado: ${r.verdict}") {
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
        KeyValue("URL pedida", r.url)
        KeyValue("URL en Chrome", r.observedPageUrl ?: "no leída")
        r.expectation?.let { KeyValue("Esperado (${it.mode})", it.text) }
        OutlinedButton(onClick = { shareText(ctx, "TalkBack run ${r.id}", file.readText()) }) { Text("Compartir JSON") }
    }
    r.selected?.let { c ->
        Section("Elemento encontrado") {
            KeyValue("Nombre accesible (propiedades del nodo, no anuncio)", c.accessibleName)
            KeyValue("Rol inferido", c.role.name)
            KeyValue("Clase", c.node.className ?: "?")
            KeyValue("Coincidencia", "${c.matchedField} · score ${"%.2f".format(c.score)}")
            KeyValue("Bounds", c.node.bounds.toString())
        }
    }
    r.strategies.forEach { StrategyView(it, r) }
    Section("Entorno") { r.environment.forEach { (k, v) -> KeyValue(k, v) } }
}

@Composable
private fun StrategyView(s: StrategyResult, r: RunResult) {
    val ctx = LocalContext.current
    Section("${s.strategy}: ${s.status}") {
        Text(ResultAssembler.describe(s.status), style = MaterialTheme.typography.bodySmall)
        val label = if (s.status == CaptureStatus.CAPTURED) "Anuncio capturado (texto enviado por TalkBack al motor TTS)"
        else "Texto recibido (NO confirmado como anuncio del elemento)"
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(if (s.concatenated.isEmpty()) "— nada —" else "“${s.concatenated}”", style = MaterialTheme.typography.titleMedium)
        s.expectationMet?.let { Text(if (it) "Cumple lo esperado" else "No cumple lo esperado", color = if (it) Ok else Bad) }

        if (s.fragments.isNotEmpty()) {
            Text("Fragmentos originales (${s.fragments.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            val from = s.steps.lastOrNull()?.actionAtElapsed
            s.fragments.forEach { UtteranceRow(it, ctx.packageName, from) }
        }
        if (s.steps.isNotEmpty()) {
            Text("Pasos", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            s.steps.forEach { st ->
                Mono(
                    "${st.index}. ${st.action} · aceptada=${st.actionAccepted} · foco en objetivo=${st.focusOnTarget}\n" +
                        "   foco: ${st.focusedDescription ?: "ninguno"}\n" +
                        "   TTS: " + st.utterances.joinToString(" | ") { "\"${it.text}\" [${callerLabel(it, ctx.packageName)}, ${statusLabel(it.status)}]" }
                            .ifEmpty { "—" },
                )
            }
        }
        if (s.focusEvents.isNotEmpty()) {
            Text("Eventos de accesibilidad (${s.focusEvents.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            s.focusEvents.takeLast(20).forEach { Mono("${wallTime(it.atWallMillis)} ${it.type} ${it.text}") }
        }
        if (s.otherUtterances.isNotEmpty()) {
            Text("Otros textos TTS en la ventana (no TalkBack): ${s.otherUtterances.size}", style = MaterialTheme.typography.bodySmall)
        }
        s.notes.forEach { Text("⚠ $it", color = Warn, style = MaterialTheme.typography.bodySmall) }
        s.screenshotPath?.let { path ->
            val bmp = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "Screenshot ${s.strategy} de ${r.id}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 6.dp),
                )
            }
        }
    }
}
