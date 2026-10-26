package dev.tbagent.core

/**
 * Lifecycle of one text that a client (normally TalkBack) asked our TTS engine to synthesize.
 *
 * - REQUESTED: onSynthesizeText was called; the text is known.
 * - SYNTHESIZING: we started delivering audio for it.
 * - SYNTHESIS_COMPLETED: we delivered all audio and called done(). This does NOT prove the
 *   audio was played to the end: playback happens afterwards in the framework.
 * - INTERRUPTED: the framework called onStop() (or refused audio) before synthesis finished,
 *   e.g. TalkBack flushed the queue because focus moved.
 * - ERROR: synthesis could not start or failed.
 */
enum class SynthesisStatus { REQUESTED, SYNTHESIZING, SYNTHESIS_COMPLETED, INTERRUPTED, ERROR }

data class Utterance(
    val id: Long,
    val receivedAtElapsed: Long,
    val receivedAtWallMillis: Long,
    val text: String,
    val callerUid: Int,
    val callerPackage: String?,
    val language: String,
    val country: String,
    val variant: String,
    val voiceName: String?,
    val speechRate: Int,
    val pitch: Int,
    val params: Map<String, String>,
    val spans: List<String>,
    val status: SynthesisStatus,
    val finishedAtElapsed: Long? = null,
    val plannedAudioMs: Long = 0,
    val deliveredAudioMs: Long = 0,
) {
    val isFromTalkBack: Boolean get() = TalkBackPackages.isTalkBack(callerPackage)
    val isFinal: Boolean
        get() = status == SynthesisStatus.SYNTHESIS_COMPLETED ||
            status == SynthesisStatus.INTERRUPTED ||
            status == SynthesisStatus.ERROR
}

object TalkBackPackages {
    const val GOOGLE = "com.google.android.marvin.talkback"
    const val SAMSUNG = "com.samsung.android.accessibility.talkback"
    val KNOWN = listOf(GOOGLE, SAMSUNG)

    fun isTalkBack(pkg: String?): Boolean =
        pkg != null && (pkg in KNOWN || pkg.contains("talkback", ignoreCase = true))
}

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
    override fun toString(): String = "[$left,$top][$right,$bottom]"
}

/** One accessibility event observed by our controller service (not by TalkBack). */
data class A11yEventRecord(
    val id: Long,
    val atElapsed: Long,
    val atWallMillis: Long,
    val type: String,
    val packageName: String?,
    val className: String?,
    val text: String,
    val contentDescription: String?,
    val bounds: Bounds?,
)

enum class Role { EDIT_TEXT, BUTTON, LINK, CHECKBOX, RADIO, SWITCH, COMBOBOX, HEADING, GROUP, IMAGE, LIST, TABLE, TEXT, OTHER }

/**
 * Platform-independent copy of the properties of an AccessibilityNodeInfo. These are the
 * INPUTS TalkBack uses; they are never presented as what TalkBack said.
 */
data class NodeSnapshot(
    /** Child-index path from the web root, e.g. "0.3.1". */
    val path: String,
    /** Pre-order index in the tree (document order). */
    val order: Int,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val stateDescription: String? = null,
    val tooltipText: String? = null,
    val paneTitle: String? = null,
    val viewId: String? = null,
    val labeledByText: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val isFocusable: Boolean = false,
    val isScreenReaderFocusable: Boolean = false,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true,
    val isHeading: Boolean = false,
    val childCount: Int = 0,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
) {
    val isInteractive: Boolean
        get() = isEditable || isClickable || isCheckable || isFocusable || isScreenReaderFocusable

    fun isAncestorOf(other: NodeSnapshot): Boolean = other.path.startsWith("$path.")
}

enum class NavigationStrategy { DIRECT_FOCUS, LINEAR_NAVIGATION }

data class TargetQuery(val name: String, val role: Role? = null)

data class Expectation(val text: String, val mode: Mode) {
    enum class Mode { CONTAINS, EQUALS }
}

data class RunSpec(
    val url: String,
    val target: TargetQuery,
    val strategies: List<NavigationStrategy>,
    val expectation: Expectation? = null,
    /** Set when the user picked one of several ambiguous candidates. */
    val candidateKey: String? = null,
    val instruction: String = "",
)

data class Candidate(
    val node: NodeSnapshot,
    val role: Role,
    val accessibleName: String,
    val score: Double,
    val matchedField: String,
    /** Stable across reloads: role | normalized name | occurrence among equal role+name. */
    val key: String,
)

/**
 * - CAPTURED: TalkBack sent text to our engine after the action and focus is confirmed on the target.
 * - CAPTURED_FOCUS_UNCONFIRMED: TalkBack spoke, but we could not confirm focus is on the target.
 * - NO_SPEECH_CAPTURED: the action ran but our engine received nothing from TalkBack.
 * - NAVIGATION_FAILED: the focus action / gesture was rejected or never reached the target.
 * - NOT_RUN: strategy was skipped.
 */
enum class CaptureStatus { CAPTURED, CAPTURED_FOCUS_UNCONFIRMED, NO_SPEECH_CAPTURED, NAVIGATION_FAILED, NOT_RUN }

enum class Verdict { PASS, FAIL, OBSERVED, INCONCLUSIVE }

data class StepRecord(
    val index: Int,
    val action: String,
    val actionAtElapsed: Long,
    val actionAtWallMillis: Long,
    val actionAccepted: Boolean,
    val focusedDescription: String?,
    val focusOnTarget: Boolean,
    val utterances: List<Utterance>,
)

data class StrategyResult(
    val strategy: NavigationStrategy,
    val status: CaptureStatus,
    val steps: List<StepRecord>,
    /** TalkBack utterances of the step that reached the target, in arrival order. */
    val fragments: List<Utterance>,
    /** Utterances in the same window that did not come from TalkBack. */
    val otherUtterances: List<Utterance>,
    val concatenated: String,
    val focusEvents: List<A11yEventRecord>,
    val screenshotPath: String?,
    val notes: List<String>,
    val expectationMet: Boolean?,
)

data class RunResult(
    val id: String,
    val startedAtWallMillis: Long,
    val finishedAtWallMillis: Long,
    val instruction: String,
    val url: String,
    val observedPageUrl: String?,
    val target: TargetQuery,
    val selected: Candidate?,
    val strategies: List<StrategyResult>,
    val expectation: Expectation?,
    val verdict: Verdict,
    val reasons: List<String>,
    val environment: Map<String, String>,
)
