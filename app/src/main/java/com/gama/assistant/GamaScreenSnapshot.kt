package com.gama.assistant

data class GamaScreenSnapshot(
    val packageName: String,
    val title: String,
    val lines: List<String>,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    fun plainText(maxChars: Int = 2400): String =
        lines.joinToString(". ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

    fun localSummary(): String {
        if (lines.isEmpty()) return "Não encontrei texto legível nessa tela."
        val useful = lines
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 3 }
            .distinct()
            .take(14)
        if (useful.isEmpty()) return "Não encontrei texto legível nessa tela."
        return "Na tela eu consigo ler: " + useful.joinToString(". ").take(2400) + "."
    }

    fun searchQuery(maxChars: Int = 220): String {
        val candidates = lines
            .map { it.trim() }
            .filter { it.length in 4..160 }
            .distinct()
        return candidates
            .take(4)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(maxChars)
            .trim()
    }
}
