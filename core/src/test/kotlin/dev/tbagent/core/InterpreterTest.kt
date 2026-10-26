package dev.tbagent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class InterpreterTest {
    private val interpreter = RuleBasedInterpreter()
    private val url = "https://example.com/form"

    private fun ok(instruction: String, u: String? = url): RunSpec {
        val r = interpreter.interpret(instruction, u)
        assertIs<Interpretation.Ok>(r, "instruction: $instruction")
        return r.spec
    }

    @Test fun findPrimaryAddress() {
        val s = ok("Find Primary Address and tell me what TalkBack says.")
        assertEquals("Primary Address", s.target.name)
        assertNull(s.target.role)
        assertEquals(url, s.url)
        assertEquals(2, s.strategies.size)
    }

    @Test fun navigateToSubmitButton() {
        val s = ok("Navigate to the Submit button.")
        assertEquals(TargetQuery("Submit", Role.BUTTON), s.target)
    }

    @Test fun findAddressGroup() {
        val s = ok("Find the Address group.")
        assertEquals(TargetQuery("Address", Role.GROUP), s.target)
    }

    @Test fun announcesOnEmailField() {
        val s = ok("Tell me what TalkBack announces on the Email field.")
        assertEquals(TargetQuery("Email", Role.EDIT_TEXT), s.target)
    }

    @Test fun spanishBuscá() {
        val s = ok("Abrí esta página, buscá Primary Address y decime exactamente qué anuncia TalkBack cuando llegás a ese elemento")
        assertEquals("Primary Address", s.target.name)
        assertNull(s.expectation)
    }

    @Test fun spanishUrlInInstructionAndCampo() {
        val s = ok("Validá https://example.com. Andá al campo Primary Address y decime qué anuncia TalkBack", u = "")
        assertEquals("https://example.com", s.url)
        assertEquals(TargetQuery("Primary Address", Role.EDIT_TEXT), s.target)
    }

    @Test fun quotedNameWithRole() {
        val s = ok("Find \"Primary Address\" field and tell me what TalkBack says")
        assertEquals(TargetQuery("Primary Address", Role.EDIT_TEXT), s.target)
    }

    @Test fun spanishBotón() {
        val s = ok("Navegá hasta el botón Enviar")
        assertEquals(TargetQuery("Enviar", Role.BUTTON), s.target)
    }

    @Test fun bareName() {
        val s = ok("Primary Address")
        assertEquals("Primary Address", s.target.name)
    }

    @Test fun expectationContains() {
        val s = ok("Find the Email field. It should announce \"Email, edit box\"")
        assertEquals(TargetQuery("Email", Role.EDIT_TEXT), s.target)
        assertEquals(Expectation("Email, edit box", Expectation.Mode.CONTAINS), s.expectation)
    }

    @Test fun expectationExactSpanish() {
        val s = ok("Buscá Primary Address. Debería anunciar exactamente \"Primary Address, cuadro de edición\"")
        assertEquals("Primary Address", s.target.name)
        assertEquals(Expectation("Primary Address, cuadro de edición", Expectation.Mode.EQUALS), s.expectation)
    }

    @Test fun linearOnly() {
        val s = ok("Find Primary Address by swiping")
        assertEquals(listOf(NavigationStrategy.LINEAR_NAVIGATION), s.strategies)
    }

    @Test fun missingUrlAsksForClarification() {
        assertIs<Interpretation.NeedsClarification>(interpreter.interpret("Find Primary Address", null))
    }

    @Test fun urlWithoutScheme() {
        assertEquals("https://example.com", RuleBasedInterpreter.normalizeUrl("example.com"))
    }
}
