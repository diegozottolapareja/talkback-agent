package dev.tbagent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MatcherTest {
    private var order = 0
    private fun node(
        path: String,
        cls: String = "android.view.View",
        text: String? = null,
        desc: String? = null,
        hint: String? = null,
        editable: Boolean = false,
        clickable: Boolean = false,
        focusable: Boolean = false,
        chromeRole: String? = null,
    ) = NodeSnapshot(
        path = path, order = order++, className = cls, text = text, contentDescription = desc, hintText = hint,
        isEditable = editable, isClickable = clickable, isFocusable = focusable,
        extras = chromeRole?.let { mapOf(NodeRoles.CHROME_ROLE_EXTRA to it) } ?: emptyMap(),
        bounds = Bounds(0, order * 10, 100, order * 10 + 9),
    )

    private val page = listOf(
        node("0.0", cls = "android.widget.TextView", text = "Contact form", chromeRole = "heading"),
        node("0.1", cls = "android.widget.TextView", text = "Primary Address *", chromeRole = "staticText"),
        node("0.2", cls = "android.widget.EditText", hint = "Primary Address *", editable = true, focusable = true, chromeRole = "textField"),
        node("0.3", cls = "android.widget.EditText", hint = "Secondary Address", editable = true, focusable = true),
        node("0.4", cls = "android.view.View", desc = "Address", chromeRole = "group"),
        node("0.5", cls = "android.widget.Button", text = "Submit", clickable = true, focusable = true, chromeRole = "button"),
        node("0.6", cls = "android.view.View", text = "Submit feedback", clickable = true, focusable = true, chromeRole = "link"),
    )

    @Test fun picksEditFieldOverItsLabel() {
        val d = ElementMatcher.decide(ElementMatcher.rank(page, TargetQuery("Primary Address")))
        assertIs<MatchDecision.Unique>(d)
        assertEquals("0.2", d.candidate.node.path)
        assertEquals(Role.EDIT_TEXT, d.candidate.role)
    }

    @Test fun roleDisambiguatesButton() {
        val d = ElementMatcher.decide(ElementMatcher.rank(page, TargetQuery("Submit", Role.BUTTON)))
        assertIs<MatchDecision.Unique>(d)
        assertEquals("0.5", d.candidate.node.path)
    }

    @Test fun groupRole() {
        val d = ElementMatcher.decide(ElementMatcher.rank(page, TargetQuery("Address", Role.GROUP)))
        assertIs<MatchDecision.Unique>(d)
        assertEquals("0.4", d.candidate.node.path)
    }

    @Test fun ambiguousWithoutRole() {
        val d = ElementMatcher.decide(ElementMatcher.rank(page, TargetQuery("Address")))
        assertIs<MatchDecision.Ambiguous>(d)
    }

    @Test fun notFound() {
        assertEquals(MatchDecision.NotFound, ElementMatcher.decide(ElementMatcher.rank(page, TargetQuery("Phone number"))))
    }

    @Test fun keyIsStableAndFindable() {
        val ranked = ElementMatcher.rank(page, TargetQuery("Address"))
        val chosen = ranked.first { it.node.path == "0.3" }
        val again = ElementMatcher.findByKey(page, TargetQuery("Address"), chosen.key)
        assertEquals("0.3", again?.node?.path)
    }

    @Test fun anchorIsPreviousSpeakableNode() {
        val target = page[2]
        assertEquals("0.1", LinearPlanner.anchorFor(page, target)?.path)
    }

    @Test fun identifierWords() {
        assertEquals("primary address", TextNormalizer.identifierWords("com.android.chrome:id/primaryAddress"))
        assertEquals("primary address", TextNormalizer.identifierWords("primary_address"))
    }
}

class CaptureTest {
    private fun u(id: Long, at: Long, text: String, pkg: String? = TalkBackPackages.GOOGLE,
                  status: SynthesisStatus = SynthesisStatus.SYNTHESIS_COMPLETED, finished: Long? = at + 500) =
        Utterance(id, at, at, text, 10000, pkg, "eng", "USA", "", null, 100, 100, emptyMap(), emptyList(), status, finished)

    private val timing = CaptureTiming(quietMs = 1000, noSpeechTimeoutMs = 3000, maxWindowMs = 10000)

    @Test fun waitsWhileSynthesizing() {
        val list = listOf(u(1, 1100, "Primary Address", status = SynthesisStatus.SYNTHESIZING, finished = null))
        assertFalse(AnnouncementCorrelator.isQuiet(list, 1000, 5000, timing))
    }

    @Test fun quietAfterLastFinished() {
        val list = listOf(u(1, 1100, "Primary Address", finished = 1600), u(2, 2000, "Double tap to edit", finished = 2400))
        assertFalse(AnnouncementCorrelator.isQuiet(list, 1000, 3000, timing))
        assertTrue(AnnouncementCorrelator.isQuiet(list, 1000, 3400, timing))
    }

    @Test fun noSpeechTimeout() {
        assertFalse(AnnouncementCorrelator.isQuiet(emptyList(), 1000, 3500, timing))
        assertTrue(AnnouncementCorrelator.isQuiet(emptyList(), 1000, 4000, timing))
    }

    @Test fun nonTalkBackSpeechIsIgnoredForQuiescence() {
        val list = listOf(u(1, 1100, "self test", pkg = "dev.tbagent"))
        assertTrue(AnnouncementCorrelator.isQuiet(list, 1000, 4000, timing))
    }

    @Test fun windowAndConcatenate() {
        val list = listOf(u(1, 900, "old"), u(2, 1100, "Primary Address, edit box"), u(3, 1500, " Double tap to activate "))
        val w = AnnouncementCorrelator.inWindow(list, 1000, 2000)
        assertEquals(listOf(2L, 3L), w.map { it.id })
        assertEquals("Primary Address, edit box Double tap to activate", AnnouncementCorrelator.concatenate(w))
    }

    private fun sr(status: CaptureStatus, text: String) = StrategyResult(
        NavigationStrategy.DIRECT_FOCUS, status, emptyList(), emptyList(), emptyList(), text, emptyList(), null, emptyList(), null,
    )

    @Test fun verdicts() {
        val exp = Expectation("primary address edit box", Expectation.Mode.CONTAINS)
        assertEquals(Verdict.PASS, ResultAssembler.verdict(listOf(sr(CaptureStatus.CAPTURED, "Primary Address, Edit box.")), exp, emptyList()).first)
        assertEquals(Verdict.FAIL, ResultAssembler.verdict(listOf(sr(CaptureStatus.CAPTURED, "Edit box")), exp, emptyList()).first)
        assertEquals(Verdict.OBSERVED, ResultAssembler.verdict(listOf(sr(CaptureStatus.CAPTURED, "x")), null, emptyList()).first)
        val (v, reasons) = ResultAssembler.verdict(listOf(sr(CaptureStatus.CAPTURED_FOCUS_UNCONFIRMED, "x")), exp, emptyList())
        assertEquals(Verdict.INCONCLUSIVE, v)
        assertTrue(reasons.isNotEmpty())
    }

    @Test fun statusMapping() {
        assertEquals(CaptureStatus.NAVIGATION_FAILED, ResultAssembler.strategyStatus(false, false, 3))
        assertEquals(CaptureStatus.NO_SPEECH_CAPTURED, ResultAssembler.strategyStatus(true, true, 0))
        assertEquals(CaptureStatus.CAPTURED, ResultAssembler.strategyStatus(true, true, 1))
        assertEquals(CaptureStatus.CAPTURED_FOCUS_UNCONFIRMED, ResultAssembler.strategyStatus(true, false, 1))
    }
}
