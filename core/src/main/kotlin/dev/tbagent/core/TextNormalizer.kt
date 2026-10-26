package dev.tbagent.core

import java.text.Normalizer

object TextNormalizer {
    private val diacritics = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")
    private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])")

    /** Lowercase, strip accents and punctuation, collapse whitespace. */
    fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD).replace(diacritics, "")
        return decomposed.lowercase().replace(nonAlnum, " ").trim()
    }

    fun tokens(s: String?): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /** "com.android.chrome:id/primary_address" or "primaryAddress" -> "primary address". */
    fun identifierWords(id: String?): String {
        if (id.isNullOrBlank()) return ""
        val local = id.substringAfterLast('/')
        return normalize(local.replace(camelBoundary, " "))
    }
}
