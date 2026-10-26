package dev.tbagent.core

sealed interface MatchDecision {
    data class Unique(val candidate: Candidate) : MatchDecision
    data class Ambiguous(val candidates: List<Candidate>) : MatchDecision
    data object NotFound : MatchDecision
}

/** Finds the node the user described by its accessible name and (optionally) its role. */
object ElementMatcher {
    const val MIN_SCORE = 0.45
    const val AMBIGUITY_MARGIN = 0.12
    private const val INTERACTIVE_BONUS = 0.15
    private const val ROLE_MATCH_BONUS = 0.3
    private const val ROLE_MISMATCH_PENALTY = 0.25

    fun accessibleName(n: NodeSnapshot): String =
        listOf(n.contentDescription, n.labeledByText, n.hintText, n.text, n.paneTitle, n.tooltipText)
            .firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

    fun rank(nodes: List<NodeSnapshot>, query: TargetQuery): List<Candidate> {
        val q = TextNormalizer.normalize(query.name)
        if (q.isEmpty()) return emptyList()

        val scored = nodes.asSequence()
            .filter { it.className != "android.webkit.WebView" }
            .mapNotNull { node -> score(node, q, query.role) }
            .filter { it.score >= MIN_SCORE }
            .toList()

        // A <label> rendered as static text duplicates the control it labels: keep the control.
        val interactiveNames = scored.filter { it.node.isInteractive }
            .map { TextNormalizer.normalize(it.accessibleName) }.toSet()
        val deduped = scored.filterNot {
            !it.node.isInteractive && TextNormalizer.normalize(it.accessibleName) in interactiveNames
        }

        val withKeys = assignKeys(deduped.sortedBy { it.node.order })
        return withKeys.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.node.order })
    }

    fun decide(ranked: List<Candidate>): MatchDecision {
        if (ranked.isEmpty()) return MatchDecision.NotFound
        if (ranked.size == 1) return MatchDecision.Unique(ranked[0])
        val best = ranked[0]
        val second = ranked[1]
        return if (best.score - second.score >= AMBIGUITY_MARGIN) {
            MatchDecision.Unique(best)
        } else {
            MatchDecision.Ambiguous(ranked.take(8))
        }
    }

    fun findByKey(nodes: List<NodeSnapshot>, query: TargetQuery, key: String): Candidate? =
        rank(nodes, query).firstOrNull { it.key == key }

    private fun score(node: NodeSnapshot, q: String, role: Role?): Candidate? {
        val fields = buildList {
            add("contentDescription" to (node.contentDescription to 1.0))
            add("labeledBy" to (node.labeledByText to 1.0))
            add("hintText" to (node.hintText to 1.0))
            // For editable fields "text" is the current value, a weaker signal than the label.
            add("text" to (node.text to if (node.isEditable) 0.8 else 1.0))
            add("paneTitle" to (node.paneTitle to 0.9))
            add("tooltip" to (node.tooltipText to 0.7))
            add("viewId" to (TextNormalizer.identifierWords(node.viewId) to 0.8))
        }
        var bestField = ""
        var best = 0.0
        for ((name, pair) in fields) {
            val (value, weight) = pair
            val s = fieldScore(q, TextNormalizer.normalize(value)) * weight
            if (s > best) {
                best = s
                bestField = name
            }
        }
        if (best <= 0.0) return null

        val nodeRole = NodeRoles.infer(node)
        var total = best
        if (node.isInteractive) total += INTERACTIVE_BONUS
        if (role != null) total += roleAdjustment(role, nodeRole)

        return Candidate(
            node = node,
            role = nodeRole,
            accessibleName = accessibleName(node).ifEmpty { node.viewId ?: "" },
            score = total,
            matchedField = bestField,
            key = "",
        )
    }

    internal fun fieldScore(q: String, f: String): Double {
        if (q.isEmpty() || f.isEmpty()) return 0.0
        if (f == q) return 1.0
        val qt = q.split(' ')
        val ft = f.split(' ').toSet()
        val coverage = qt.count { it in ft }.toDouble() / qt.size
        if (coverage == 1.0) {
            val extra = (ft.size - qt.size).coerceAtLeast(0)
            return (0.9 - 0.05 * extra).coerceAtLeast(0.6)
        }
        if (f.contains(q)) return 0.6
        return 0.5 * coverage
    }

    private fun roleAdjustment(wanted: Role, actual: Role): Double = when {
        wanted == actual -> ROLE_MATCH_BONUS
        wanted == Role.EDIT_TEXT && actual == Role.COMBOBOX -> ROLE_MATCH_BONUS / 2
        wanted == Role.BUTTON && actual == Role.LINK -> 0.0
        else -> -ROLE_MISMATCH_PENALTY
    }

    private fun assignKeys(inDocumentOrder: List<Candidate>): List<Candidate> {
        val seen = HashMap<String, Int>()
        return inDocumentOrder.map { c ->
            val base = "${c.role}|${TextNormalizer.normalize(c.accessibleName)}"
            val n = seen.getOrDefault(base, 0)
            seen[base] = n + 1
            c.copy(key = "$base|$n")
        }
    }
}
