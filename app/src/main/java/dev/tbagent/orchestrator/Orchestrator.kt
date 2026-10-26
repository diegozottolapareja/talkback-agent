package dev.tbagent.orchestrator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import dev.tbagent.Graph
import dev.tbagent.a11y.ControllerService
import dev.tbagent.a11y.NodeSnapshots
import dev.tbagent.core.AnnouncementCorrelator
import dev.tbagent.core.Candidate
import dev.tbagent.core.CaptureStatus
import dev.tbagent.core.CaptureTiming
import dev.tbagent.core.ElementMatcher
import dev.tbagent.core.ExpectationEvaluator
import dev.tbagent.core.LinearPlanner
import dev.tbagent.core.MatchDecision
import dev.tbagent.core.NavigationStrategy
import dev.tbagent.core.NodeSnapshot
import dev.tbagent.core.ResultAssembler
import dev.tbagent.core.RunResult
import dev.tbagent.core.RunSpec
import dev.tbagent.core.StepRecord
import dev.tbagent.core.StrategyResult
import dev.tbagent.core.SynthesisStatus
import dev.tbagent.core.Utterance
import dev.tbagent.core.Verdict
import dev.tbagent.env.EnvironmentInspector
import dev.tbagent.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface RunState {
    data object Idle : RunState
    data class Running(val step: String) : RunState
    data class NeedsSelection(val spec: RunSpec, val candidates: List<Candidate>) : RunState
    data class Finished(val result: RunResult, val file: File) : RunState
    data class Failed(val message: String) : RunState
}

/**
 * Opens Chrome, finds the element, moves focus with each strategy and correlates the
 * resulting accessibility events and TTS requests. Never synthesizes an announcement from
 * node properties: the announcement is only what our TTS engine received from TalkBack.
 */
class Orchestrator(private val ctx: Context) {
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var job: Job? = null
    private val timing = CaptureTiming()

    val isRunning: Boolean get() = job?.isActive == true

    fun start(spec: RunSpec) {
        if (isRunning) return
        _log.value = emptyList()
        job = Graph.scope.launch {
            _state.value = try {
                execute(spec)
            } catch (c: CancellationException) {
                RunState.Failed("Ejecución cancelada.")
            } catch (t: Throwable) {
                Log.e(TAG, "run failed", t)
                RunState.Failed("Error inesperado: ${t.javaClass.simpleName}: ${t.message}")
            }
            bringAppToFront()
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        if (!isRunning) _state.value = RunState.Idle
    }

    private fun step(msg: String) {
        Log.i(TAG, msg)
        _log.value = _log.value + "${clock()} $msg"
        _state.value = RunState.Running(msg)
    }

    private suspend fun execute(spec: RunSpec): RunState {
        val started = System.currentTimeMillis()
        val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(started))
        val dir = Graph.runStore.dirFor(runId)

        val setup = EnvironmentInspector.status(ctx)
        val problems = mutableListOf<String>()
        if (!setup.talkBackEnabled) problems += "TalkBack no está activo."
        if (!setup.ourEngineSelected) {
            problems += "El motor TTS preferido no es 'TalkBack Capture' (actual: ${setup.defaultEngine ?: "desconocido"})."
        }
        val service = ControllerService.instance
            ?: return RunState.Failed("El servicio 'TalkBack Agent Controller' no está activo. Activalo en la pestaña Setup.")
        if (!setup.chromeInstalled) return RunState.Failed("Chrome no está instalado.")

        step("Abriendo Chrome: ${spec.url}")
        openInChrome(spec.url)

        step("Esperando que cargue la página…")
        val page = waitForPage(service, spec.url)
            ?: return RunState.Failed(
                "No se detectó contenido web de Chrome en ${PAGE_TIMEOUT_MS / 1000} s. " +
                    "¿Chrome mostró una pantalla de bienvenida o de inicio de sesión?",
            )
        var captured = page.second
        val observedUrl = service.chromeRoot()?.let { service.chromeUrlBarText(it) }

        step("Buscando \"${spec.target.name}\" (${captured.snapshots.size} nodos)…")
        var decision = decide(spec, captured.snapshots)
        var scrolls = 0
        while (decision == MatchDecision.NotFound && scrolls < MAX_SCROLLS) {
            val web = NodeSnapshots.findWebView(service.chromeRoot() ?: break) ?: break
            if (!web.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            scrolls++
            step("No encontrado; scroll $scrolls/$MAX_SCROLLS…")
            delay(900)
            captured = NodeSnapshots.capture(NodeSnapshots.findWebView(service.chromeRoot() ?: break) ?: break)
            decision = decide(spec, captured.snapshots)
        }

        val candidate = when (decision) {
            is MatchDecision.Unique -> decision.candidate
            is MatchDecision.Ambiguous -> {
                step("Varios elementos coinciden; se necesita elegir.")
                return RunState.NeedsSelection(spec, decision.candidates)
            }
            MatchDecision.NotFound -> {
                val names = captured.snapshots.filter { it.isInteractive }
                    .map { ElementMatcher.accessibleName(it) }.filter { it.isNotBlank() }.distinct().take(15)
                return RunState.Failed(
                    "No encontré \"${spec.target.name}\". Elementos interactivos visibles: " +
                        names.joinToString(" · ").ifEmpty { "(ninguno)" },
                )
            }
        }
        val targetNode = captured.nodes[candidate.node.path]
            ?: return RunState.Failed("El nodo encontrado ya no existe en el árbol.")
        step("Elemento: ${candidate.role} \"${candidate.accessibleName}\" (campo ${candidate.matchedField})")

        val results = spec.strategies.map { strategy ->
            when (strategy) {
                NavigationStrategy.DIRECT_FOCUS -> runDirect(service, targetNode, candidate.node, dir, spec)
                NavigationStrategy.LINEAR_NAVIGATION -> runLinear(service, captured, targetNode, candidate.node, dir, spec)
            }
        }

        if (!Graph.settings.captureValidated) {
            problems += "El canal de captura todavía no fue validado en este dispositivo (Diagnóstico → Milestone 1)."
        }
        var (verdict, reasons) = ResultAssembler.verdict(results, spec.expectation, problems)
        if (!Graph.settings.captureValidated && verdict != Verdict.INCONCLUSIVE) {
            reasons = reasons + "Veredicto ${verdict.name} rebajado a INCONCLUSIVE hasta validar la captura."
            verdict = Verdict.INCONCLUSIVE
        }

        val result = RunResult(
            id = runId,
            startedAtWallMillis = started,
            finishedAtWallMillis = System.currentTimeMillis(),
            instruction = spec.instruction,
            url = spec.url,
            observedPageUrl = observedUrl,
            target = spec.target,
            selected = candidate,
            strategies = results,
            expectation = spec.expectation,
            verdict = verdict,
            reasons = reasons,
            environment = EnvironmentInspector.environmentMap(ctx),
        )
        val file = Graph.runStore.save(result)
        step("Listo: ${verdict.name}")
        return RunState.Finished(result, file)
    }

    private fun decide(spec: RunSpec, nodes: List<NodeSnapshot>): MatchDecision {
        val key = spec.candidateKey
        if (key != null) {
            return ElementMatcher.findByKey(nodes, spec.target, key)?.let { MatchDecision.Unique(it) } ?: MatchDecision.NotFound
        }
        return ElementMatcher.decide(ElementMatcher.rank(nodes, spec.target))
    }

    // ---- strategies -------------------------------------------------------------------------

    private suspend fun runDirect(
        service: ControllerService,
        target: AccessibilityNodeInfo,
        targetSnap: NodeSnapshot,
        dir: File,
        spec: RunSpec,
    ): StrategyResult {
        step("Direct Focus: preparando…")
        val notes = mutableListOf<String>()
        ensureOnScreen(target, notes)
        clearFocusIfOn(service, target)
        drainSpeech()

        step("Direct Focus: ACTION_ACCESSIBILITY_FOCUS")
        val at = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val accepted = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (!accepted) notes += "performAction(ACTION_ACCESSIBILITY_FOCUS) devolvió false."
        awaitWindow(at)
        val end = SystemClock.elapsedRealtime()

        val focused = service.accessibilityFocused()
        val onTarget = sameNode(focused, target, targetSnap)
        if (!onTarget) notes += "Foco de accesibilidad al final: ${NodeSnapshots.describe(focused) ?: "ninguno"}."
        val window = AnnouncementCorrelator.inWindow(Graph.utterances.snapshot(), at, end)
        val step = StepRecord(1, "ACTION_ACCESSIBILITY_FOCUS", at, wall, accepted, NodeSnapshots.describe(focused), onTarget, window)
        val shot = screenshot(service, File(dir, "direct_focus.png"), notes)
        return buildResult(NavigationStrategy.DIRECT_FOCUS, listOf(step), accepted, onTarget, at, end, shot, notes, spec)
    }

    private suspend fun runLinear(
        service: ControllerService,
        captured: NodeSnapshots.Captured,
        target: AccessibilityNodeInfo,
        targetSnap: NodeSnapshot,
        dir: File,
        spec: RunSpec,
    ): StrategyResult {
        step("Linear Navigation: preparando…")
        val notes = mutableListOf<String>()
        val anchorSnap = LinearPlanner.anchorFor(captured.snapshots, targetSnap)
        val anchor = anchorSnap?.let { captured.nodes[it.path] }
        if (anchor == null) {
            notes += "No hay un elemento anterior desde el cual navegar (¿es el primero de la página?)."
            return notRun(NavigationStrategy.LINEAR_NAVIGATION, notes)
        }
        ensureOnScreen(anchor, notes)
        clearFocusIfOn(service, anchor)
        drainSpeech()

        val steps = mutableListOf<StepRecord>()
        val anchorAt = SystemClock.elapsedRealtime()
        val anchorWall = System.currentTimeMillis()
        val anchorOk = anchor.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        awaitWindow(anchorAt)
        steps += StepRecord(
            0, "ACTION_ACCESSIBILITY_FOCUS (punto de partida)", anchorAt, anchorWall, anchorOk,
            NodeSnapshots.describe(service.accessibilityFocused()), false,
            AnnouncementCorrelator.inWindow(Graph.utterances.snapshot(), anchorAt, SystemClock.elapsedRealtime()),
        )
        if (!anchorOk) notes += "No se pudo poner el foco en el punto de partida."

        var reached = false
        var gestureOk = true
        var lastAt = anchorAt
        var lastEnd = SystemClock.elapsedRealtime()
        for (i in 1..MAX_LINEAR_STEPS) {
            val before = service.accessibilityFocused()
            step("Linear Navigation: swipe → ($i/$MAX_LINEAR_STEPS)")
            val at = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val ok = service.swipeHorizontal(toTheRight = true)
            awaitWindow(at)
            val end = SystemClock.elapsedRealtime()
            val after = service.accessibilityFocused()
            val onTarget = sameNode(after, target, targetSnap)
            steps += StepRecord(
                i, "dispatchGesture swipe →", at, wall, ok, NodeSnapshots.describe(after), onTarget,
                AnnouncementCorrelator.inWindow(Graph.utterances.snapshot(), at, end),
            )
            lastAt = at
            lastEnd = end
            if (!ok) {
                notes += "El sistema canceló el gesto inyectado (paso $i)."
                gestureOk = false
                break
            }
            if (onTarget) {
                reached = true
                break
            }
            if (after == null || after == before) {
                notes += "El swipe inyectado no movió el foco de accesibilidad (paso $i): " +
                    "TalkBack no parece interpretar gestos de dispatchGesture en este dispositivo."
                break
            }
        }
        if (!reached && gestureOk && steps.size > MAX_LINEAR_STEPS) {
            notes += "No se llegó al elemento en $MAX_LINEAR_STEPS swipes."
        }
        val shot = screenshot(service, File(dir, "linear_navigation.png"), notes)
        return buildResult(NavigationStrategy.LINEAR_NAVIGATION, steps, gestureOk && reached, reached, lastAt, lastEnd, shot, notes, spec)
    }

    private fun buildResult(
        strategy: NavigationStrategy,
        steps: List<StepRecord>,
        actionAccepted: Boolean,
        reachedTarget: Boolean,
        from: Long,
        to: Long,
        screenshot: String?,
        notes: List<String>,
        spec: RunSpec,
    ): StrategyResult {
        val window = steps.last().utterances
        // Non-TalkBack utterances are kept as evidence but never mixed into the announcement.
        val (talkBack, other) = window.partition(Utterance::isFromTalkBack)
        val status = ResultAssembler.strategyStatus(actionAccepted, reachedTarget, talkBack.size)
        val concatenated = AnnouncementCorrelator.concatenate(talkBack)
        val allNotes = notes.toMutableList()
        if (talkBack.any { it.status == SynthesisStatus.INTERRUPTED }) {
            allNotes += "Al menos un fragmento fue interrumpido antes de terminar su síntesis (ver estado de cada fragmento)."
        }
        if (talkBack.any { !it.isFinal }) {
            allNotes += "Algún fragmento seguía sintetizándose al cerrar la ventana de captura."
        }
        return StrategyResult(
            strategy = strategy,
            status = status,
            steps = steps,
            fragments = talkBack,
            otherUtterances = other,
            concatenated = concatenated,
            focusEvents = Graph.events.between(from, to),
            screenshotPath = screenshot,
            notes = allNotes,
            expectationMet = spec.expectation
                ?.takeIf { status == CaptureStatus.CAPTURED }
                ?.let { ExpectationEvaluator.matches(it, concatenated) },
        )
    }

    private fun notRun(strategy: NavigationStrategy, notes: List<String>) = StrategyResult(
        strategy, CaptureStatus.NOT_RUN, emptyList(), emptyList(), emptyList(), "", emptyList(), null, notes, null,
    )

    // ---- helpers ----------------------------------------------------------------------------

    private fun openInChrome(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(ControllerService.CHROME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    private suspend fun waitForPage(service: ControllerService, url: String): Pair<AccessibilityNodeInfo, NodeSnapshots.Captured>? {
        val host = Uri.parse(url).host?.removePrefix("www.")
        val deadline = SystemClock.elapsedRealtime() + PAGE_TIMEOUT_MS
        var lastCount = -1
        var stableRounds = 0
        delay(1200)
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = service.chromeRoot()
            val web = root?.let { NodeSnapshots.findWebView(it) }
            if (root != null && web != null) {
                val bar = service.chromeUrlBarText(root)
                val urlMatches = host == null || bar == null || bar.contains(host, ignoreCase = true)
                val captured = NodeSnapshots.capture(web)
                val count = captured.snapshots.size
                if (urlMatches && count > 1 && count == lastCount) {
                    stableRounds++
                    if (stableRounds >= 2) return web to captured
                } else {
                    stableRounds = 0
                }
                lastCount = count
            }
            delay(700)
        }
        return null
    }

    private suspend fun ensureOnScreen(node: AccessibilityNodeInfo, notes: MutableList<String>) {
        node.refresh()
        if (!node.isVisibleToUser) {
            val ok = node.performAction(AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            notes += "El elemento estaba fuera de pantalla; ACTION_SHOW_ON_SCREEN=$ok."
            delay(800)
            node.refresh()
        }
    }

    private suspend fun clearFocusIfOn(service: ControllerService, node: AccessibilityNodeInfo) {
        val current = service.accessibilityFocused()
        if (current != null && current == node) {
            // Re-focusing an already focused node produces no event and no speech.
            current.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            delay(400)
        }
    }

    /** Waits until TalkBack is not speaking, so the next window starts clean. */
    private suspend fun drainSpeech() {
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            val tb = Graph.utterances.snapshot().filter { it.isFromTalkBack }
            val busy = tb.any { !it.isFinal } ||
                tb.any { (it.finishedAtElapsed ?: it.receivedAtElapsed) > now - 700 }
            if (!busy) return
            delay(150)
        }
    }

    private suspend fun awaitWindow(actionAt: Long) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (AnnouncementCorrelator.isQuiet(Graph.utterances.snapshot(), actionAt, now, timing)) return
            delay(100)
        }
    }

    private fun sameNode(focused: AccessibilityNodeInfo?, target: AccessibilityNodeInfo, targetSnap: NodeSnapshot): Boolean {
        focused ?: return false
        if (focused == target) return true
        val f = NodeSnapshots.snapshot(focused, "", 0)
        return !f.bounds.isEmpty && f.bounds == NodeSnapshots.snapshot(target.also { it.refresh() }, "", 0).bounds &&
            ElementMatcher.accessibleName(f) == ElementMatcher.accessibleName(targetSnap)
    }

    private suspend fun screenshot(service: ControllerService, file: File, notes: MutableList<String>): String? {
        val error = service.screenshot(file)
        if (error != null) notes += "Screenshot no disponible: $error"
        delay(1100) // takeScreenshot is rate-limited to about one per second
        return if (error == null) file.absolutePath else null
    }

    private fun bringAppToFront() {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { (ControllerService.instance ?: ctx).startActivity(intent) }
            .onFailure { Log.w(TAG, "could not return to app", it) }
    }

    private fun clock() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val TAG = "Orchestrator"
        const val PAGE_TIMEOUT_MS = 25_000L
        const val MAX_SCROLLS = 8
        const val MAX_LINEAR_STEPS = 6
    }
}
