package dev.tbagent.core

sealed interface Interpretation {
    data class Ok(val spec: RunSpec, val notes: List<String> = emptyList()) : Interpretation
    data class NeedsClarification(val message: String) : Interpretation
}

/**
 * Turns a free-text instruction into a [RunSpec]. Implementations may be rule based, an LLM
 * API client or an on-device model; the execution engine depends only on this interface.
 */
interface InstructionInterpreter {
    fun interpret(instruction: String, url: String?): Interpretation
}

/** Offline interpreter for English and Spanish instructions. No network, no API key. */
class RuleBasedInterpreter : InstructionInterpreter {

    override fun interpret(instruction: String, url: String?): Interpretation {
        var text = instruction.trim()

        val urlInText = URL_REGEX.find(text)
        val resolvedUrl = normalizeUrl(url?.takeIf { it.isNotBlank() } ?: urlInText?.value)
            ?: return Interpretation.NeedsClarification("Falta la URL. Escribila en el campo URL o dentro de la instrucción.")
        if (urlInText != null) text = text.removeRange(urlInText.range)

        val (expectation, withoutExpectation) = extractExpectation(text)
        text = withoutExpectation

        val target = extractTarget(text)
            ?: return Interpretation.NeedsClarification(
                "No pude identificar el elemento. Escribí su nombre, por ejemplo: Find \"Primary Address\".",
            )

        val notes = mutableListOf<String>()
        val strategies = extractStrategies(instruction)
        if (strategies.size == 1) notes += "Estrategia pedida explícitamente: ${strategies.first()}"

        return Interpretation.Ok(
            RunSpec(
                url = resolvedUrl,
                target = target,
                strategies = strategies,
                expectation = expectation,
                instruction = instruction,
            ),
            notes,
        )
    }

    // ---- target ---------------------------------------------------------------------------

    internal fun extractTarget(text: String): TargetQuery? {
        QUOTED.find(text)?.let { m ->
            val name = m.groupValues.drop(1).first { it.isNotEmpty() }.trim()
            if (name.isNotEmpty()) return TargetQuery(name, roleNear(text, m.range))
        }
        for (trigger in listOf(PRIMARY_TRIGGERS, SECONDARY_TRIGGERS)) {
            for (m in trigger.findAll(text)) {
                val raw = cutAtStopper(text.substring(m.range.last + 1))
                cleanTarget(raw)?.let { return it }
            }
        }
        // Instruction is just the element name, e.g. "Primary Address" or "Submit button".
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size in 1..6) cleanTarget(text)?.let { return it }
        return null
    }

    private fun cutAtStopper(s: String): String {
        val m = STOPPER.find(s) ?: return s
        return s.substring(0, m.range.first)
    }

    internal fun cleanTarget(raw: String): TargetQuery? {
        var s = raw.trim().trim('"', '\'', '“', '”', '«', '»').trim()
        var role: Role? = null
        s = stripLeading(s, ARTICLES)
        ROLE_WORDS.firstOrNull { (w, _) -> startsWithWord(s, w) }?.let { (w, r) ->
            role = r
            s = stripLeading(s.substring(w.length).trim(), CONNECTORS)
        }
        ROLE_WORDS.firstOrNull { (w, _) -> endsWithWord(s, w) }?.let { (w, r) ->
            if (role == null) role = r
            s = s.substring(0, s.length - w.length).trim()
        }
        s = stripLeading(s, ARTICLES)
        s = s.trim().trim('"', '\'', '“', '”', '«', '»', ':', ',', '.').trim()
        val norm = TextNormalizer.normalize(s)
        if (norm.isEmpty() || norm in REJECTED || norm.startsWith("what ") || norm.startsWith("que ")) return null
        if (norm.split(' ').size > 8) return null
        return TargetQuery(s, role)
    }

    private fun roleNear(text: String, quoteRange: IntRange): Role? {
        val after = text.substring(quoteRange.last + 1).trimStart().lowercase()
        ROLE_WORDS.firstOrNull { (w, _) -> startsWithWord(after, w) }?.let { return it.second }
        val before = stripTrailing(text.substring(0, quoteRange.first).trimEnd().lowercase(), CONNECTORS)
        return ROLE_WORDS.firstOrNull { (w, _) -> endsWithWord(before, w) }?.second
    }

    // ---- expectation ----------------------------------------------------------------------

    internal fun extractExpectation(text: String): Pair<Expectation?, String> {
        val m = EXPECTATION_TRIGGER.find(text) ?: return null to text
        var body = text.substring(m.range.last + 1).trimStart()
        var exact = false
        EXACT_PREFIX.find(body)?.let {
            exact = true
            body = body.substring(it.range.last + 1)
        }
        val bodyStart = text.length - body.length
        val quoted = QUOTED.find(body)?.takeIf { it.range.first == 0 }
        val value: String
        val consumedEnd: Int
        if (quoted != null) {
            value = quoted.groupValues.drop(1).first { it.isNotEmpty() }
            consumedEnd = bodyStart + quoted.range.last + 1
        } else {
            val end = body.indexOfAny(charArrayOf('.', ';', '\n')).let { if (it < 0) body.length else it }
            value = body.substring(0, end)
            consumedEnd = bodyStart + end
        }
        if (value.isBlank()) return null to text
        val remaining = text.removeRange(m.range.first, consumedEnd)
        val mode = if (exact) Expectation.Mode.EQUALS else Expectation.Mode.CONTAINS
        return Expectation(value.trim(), mode) to remaining
    }

    // ---- strategies -----------------------------------------------------------------------

    internal fun extractStrategies(text: String): List<NavigationStrategy> {
        val linear = LINEAR_HINT.containsMatchIn(text)
        val direct = DIRECT_HINT.containsMatchIn(text)
        return when {
            linear && !direct -> listOf(NavigationStrategy.LINEAR_NAVIGATION)
            direct && !linear -> listOf(NavigationStrategy.DIRECT_FOCUS)
            else -> listOf(NavigationStrategy.DIRECT_FOCUS, NavigationStrategy.LINEAR_NAVIGATION)
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun startsWithWord(s: String, w: String): Boolean =
        s.length >= w.length && s.regionMatches(0, w, 0, w.length, ignoreCase = true) &&
            (s.length == w.length || !s[w.length].isLetterOrDigit())

    private fun endsWithWord(s: String, w: String): Boolean {
        if (s.length < w.length) return false
        val start = s.length - w.length
        return s.regionMatches(start, w, 0, w.length, ignoreCase = true) &&
            (start == 0 || !s[start - 1].isLetterOrDigit())
    }

    private fun stripLeading(s: String, words: List<String>): String {
        var out = s.trim()
        var changed = true
        while (changed) {
            changed = false
            for (w in words) {
                if (startsWithWord(out, w) && out.length > w.length) {
                    out = out.substring(w.length).trim()
                    changed = true
                }
            }
        }
        return out
    }

    private fun stripTrailing(s: String, words: List<String>): String {
        var out = s.trim()
        for (w in words) if (endsWithWord(out, w)) out = out.substring(0, out.length - w.length).trim()
        return out
    }

    companion object {
        private const val L = "(?<![\\p{L}\\p{N}])"
        private const val R = "(?![\\p{L}\\p{N}])"

        val URL_REGEX = Regex("(?:https?://|www\\.)[^\\s\"'<>]+[^\\s\"'<>.,;:!?)]")

        fun normalizeUrl(raw: String?): String? {
            val u = raw?.trim()?.trimEnd('.', ',', ';') ?: return null
            if (u.isEmpty()) return null
            if (u.startsWith("http://", true) || u.startsWith("https://", true)) return u
            if (u.contains('.') && !u.contains(' ')) return "https://$u"
            return null
        }

        private val QUOTED = Regex("\"([^\"]+)\"|“([^”]+)”|«([^»]+)»|'([^']{2,})'")

        private val PRIMARY_TRIGGERS = Regex(
            L + "(?:find|locate|search for|look for|navigate to|go to|move to|focus on|focus|select|" +
                "busc[aá]r?|busque|encontr[aá]r?|localiz[aá]r?|" +
                "and[aá] (?:hasta el|hasta la|hasta|al|a la|a)|ir (?:hasta el|hasta la|hasta|al|a la|a)|" +
                "naveg[aá]r? (?:hasta el|hasta la|hasta|al|a la|a)|mov[eé]te (?:hasta el|hasta la|hasta|al|a la|a)|" +
                "enfoc[aá]r?|posicion[aá]te (?:en el|en la|en))" + R,
            RegexOption.IGNORE_CASE,
        )

        private val SECONDARY_TRIGGERS = Regex(
            L + "(?:on|at|reach(?:es)?|lleg[aá]s? (?:al|a la|a)|hasta|en el|en la|sobre el|sobre la)" + R,
            RegexOption.IGNORE_CASE,
        )

        private val STOPPER = Regex(
            "\\s+(?:and|y|then|luego|when|cuando|to see|para|so|e)" + R + "|[,;:!?]|\\.(?:\\s|$)|\\n",
            RegexOption.IGNORE_CASE,
        )

        private val EXPECTATION_TRIGGER = Regex(
            L + "(?:expected(?: announcement)?|expect|should (?:announce|say|read|be announced as)|" +
                "must (?:announce|say)|esperado|resultado esperado|deber[ií]a (?:anunciar|decir|leer)|" +
                "tiene que (?:anunciar|decir))" + R + "\\s*:?",
            RegexOption.IGNORE_CASE,
        )

        private val EXACT_PREFIX = Regex("^(?:exactly|exactamente)\\s*:?\\s*", RegexOption.IGNORE_CASE)

        private val LINEAR_HINT = Regex(
            "(?i)swip|linear|deslizando|desliz[aá]|navegaci[oó]n lineal|gesto|gesture",
        )
        private val DIRECT_HINT = Regex("(?i)direct focus|foco directo|directamente")

        private val ARTICLES = listOf(
            "the", "a", "an", "el", "la", "los", "las", "al", "del", "un", "una", "this", "that",
            "ese", "esa", "este", "esta", "elemento", "element",
        )
        private val CONNECTORS = listOf(
            "de", "called", "named", "labeled", "labelled", "llamado", "llamada", "etiquetado", "etiquetada",
            "con la etiqueta", "with label",
        )

        // Longest first so "radio button" wins over "button".
        private val ROLE_WORDS: List<Pair<String, Role>> = listOf(
            "casilla de verificación" to Role.CHECKBOX, "lista desplegable" to Role.COMBOBOX,
            "cuadro de edición" to Role.EDIT_TEXT, "cuadro de texto" to Role.EDIT_TEXT,
            "campo de texto" to Role.EDIT_TEXT, "botón de opción" to Role.RADIO,
            "radio button" to Role.RADIO, "input field" to Role.EDIT_TEXT, "text field" to Role.EDIT_TEXT,
            "text box" to Role.EDIT_TEXT, "edit box" to Role.EDIT_TEXT, "check box" to Role.CHECKBOX,
            "combo box" to Role.COMBOBOX, "desplegable" to Role.COMBOBOX, "encabezado" to Role.HEADING,
            "checkbox" to Role.CHECKBOX, "combobox" to Role.COMBOBOX, "dropdown" to Role.COMBOBOX,
            "textbox" to Role.EDIT_TEXT, "fieldset" to Role.GROUP, "heading" to Role.HEADING,
            "casilla" to Role.CHECKBOX, "enlace" to Role.LINK, "vínculo" to Role.LINK, "vinculo" to Role.LINK,
            "button" to Role.BUTTON, "botón" to Role.BUTTON, "boton" to Role.BUTTON,
            "switch" to Role.SWITCH, "interruptor" to Role.SWITCH, "select" to Role.COMBOBOX,
            "título" to Role.HEADING, "titulo" to Role.HEADING, "imagen" to Role.IMAGE, "image" to Role.IMAGE,
            "field" to Role.EDIT_TEXT, "input" to Role.EDIT_TEXT, "campo" to Role.EDIT_TEXT,
            "group" to Role.GROUP, "grupo" to Role.GROUP, "link" to Role.LINK,
        )

        private val REJECTED = setOf(
            "it", "this", "that", "here", "page", "this page", "the page", "pagina", "esta pagina", "la pagina",
            "ese", "esa", "eso", "este", "esta", "elemento", "element", "talkback", "lo", "la", "el",
        )
    }
}
