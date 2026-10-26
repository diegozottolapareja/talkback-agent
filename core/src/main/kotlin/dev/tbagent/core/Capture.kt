package dev.tbagent.core

data class CaptureTiming(
    /** Silence after the last TalkBack activity that ends the window (usage hints arrive late). */
    val quietMs: Long = 1800,
    /** Give up waiting for the first utterance after this long. */
    val noSpeechTimeoutMs: Long = 4000,
    /** Hard cap for one window. */
    val maxWindowMs: Long = 15000,
)

/** Time-based correlation between an action and the utterances our engine received. */
object AnnouncementCorrelator {

    fun inWindow(utterances: List<Utterance>, fromElapsed: Long, toElapsed: Long): List<Utterance> =
        utterances.filter { it.receivedAtElapsed in fromElapsed..toElapsed }.sortedBy { it.receivedAtElapsed }

    /**
     * True when the window that started at [actionAtElapsed] can be closed: either the hard cap
     * passed, nothing arrived within the no-speech timeout, or TalkBack has been quiet for
     * [CaptureTiming.quietMs]. An utterance still being synthesized counts as activity "now".
     */
    fun isQuiet(utterances: List<Utterance>, actionAtElapsed: Long, nowElapsed: Long, timing: CaptureTiming): Boolean {
        val elapsed = nowElapsed - actionAtElapsed
        if (elapsed >= timing.maxWindowMs) return true
        val relevant = utterances.filter { it.receivedAtElapsed >= actionAtElapsed && it.isFromTalkBack }
        if (relevant.isEmpty()) return elapsed >= timing.noSpeechTimeoutMs
        val lastActivity = relevant.maxOf { u ->
            if (u.isFinal) (u.finishedAtElapsed ?: u.receivedAtElapsed) else nowElapsed
        }
        return nowElapsed - lastActivity >= timing.quietMs
    }

    fun concatenate(fragments: List<Utterance>): String =
        fragments.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

object ExpectationEvaluator {
    fun matches(expectation: Expectation, observed: String): Boolean {
        val e = TextNormalizer.normalize(expectation.text)
        val o = TextNormalizer.normalize(observed)
        if (e.isEmpty()) return false
        return when (expectation.mode) {
            Expectation.Mode.EQUALS -> o == e
            Expectation.Mode.CONTAINS -> o.contains(e)
        }
    }
}

object ResultAssembler {

    fun strategyStatus(actionAccepted: Boolean, reachedTarget: Boolean, talkBackFragments: Int): CaptureStatus = when {
        !actionAccepted -> CaptureStatus.NAVIGATION_FAILED
        talkBackFragments == 0 -> CaptureStatus.NO_SPEECH_CAPTURED
        reachedTarget -> CaptureStatus.CAPTURED
        else -> CaptureStatus.CAPTURED_FOCUS_UNCONFIRMED
    }

    /**
     * PASS/FAIL only from strategies whose capture is confirmed. Anything else is INCONCLUSIVE
     * with explicit reasons; we never fall back to reconstructing speech from HTML/ARIA.
     */
    fun verdict(
        strategies: List<StrategyResult>,
        expectation: Expectation?,
        preconditionProblems: List<String>,
    ): Pair<Verdict, List<String>> {
        val reasons = preconditionProblems.toMutableList()
        val captured = strategies.filter { it.status == CaptureStatus.CAPTURED }
        strategies.filter { it.status != CaptureStatus.CAPTURED && it.status != CaptureStatus.NOT_RUN }.forEach {
            reasons += "${it.strategy}: ${describe(it.status)}"
        }
        if (captured.isEmpty()) {
            if (strategies.none { it.status != CaptureStatus.NOT_RUN }) reasons += "No strategy was run."
            return Verdict.INCONCLUSIVE to reasons
        }
        if (expectation == null) return Verdict.OBSERVED to reasons
        val allMet = captured.all { ExpectationEvaluator.matches(expectation, it.concatenated) }
        return (if (allMet) Verdict.PASS else Verdict.FAIL) to reasons
    }

    fun describe(status: CaptureStatus): String = when (status) {
        CaptureStatus.CAPTURED -> "announcement captured and focus confirmed on the element"
        CaptureStatus.CAPTURED_FOCUS_UNCONFIRMED -> "TalkBack spoke, but focus on the target element could not be confirmed"
        CaptureStatus.NO_SPEECH_CAPTURED -> "our TTS engine received no text from TalkBack (is TalkBack on? is the capture engine selected?)"
        CaptureStatus.NAVIGATION_FAILED -> "the navigation action was rejected or never reached the element"
        CaptureStatus.NOT_RUN -> "not run"
    }
}

/** Chooses where Linear Navigation starts: the closest previous node TalkBack would stop on. */
object LinearPlanner {
    private fun speakable(n: NodeSnapshot) =
        n.isInteractive || !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()

    /** Nodes before the target that TalkBack would stop on, closest first. */
    fun anchorsFor(nodes: List<NodeSnapshot>, target: NodeSnapshot, limit: Int): List<NodeSnapshot> =
        nodes.asSequence()
            .filter { it.order < target.order }
            .filter { it.className != "android.webkit.WebView" }
            .filter { !it.isAncestorOf(target) && !target.isAncestorOf(it) }
            // No visibility filter: off-screen neighbours are valid anchors (they get scrolled into view).
            // Filtering them out picked a node far above the target on long pages.
            .filter { speakable(it) }
            .sortedByDescending { it.order }
            .take(limit)
            .toList()

    /** How many speakable nodes lie strictly between the anchor and the target, plus the target itself. */
    fun stepsBetween(nodes: List<NodeSnapshot>, anchor: NodeSnapshot, target: NodeSnapshot): Int =
        nodes.count { it.order > anchor.order && it.order <= target.order && speakable(it) && it.className != "android.webkit.WebView" }
}
