package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/** Ephemeral voice-session memory. Raw microphone audio is never persisted. */
class ConversationSession {
    @Volatile var active: Boolean = false
        private set
    private val turns = ArrayDeque<String>()

    @Synchronized fun open() {
        turns.clear()
        active = true
    }

    @Synchronized fun close() {
        active = false
        turns.clear()
    }

    @Synchronized fun userSaid(text: String) {
        if (active) record("Usuário", text)
    }

    @Synchronized fun gamaSaid(text: String) {
        if (active) record("Gama", text)
    }

    /** Recent dialogue window for the local brain. */
    @Synchronized fun historyBeforeCurrent(maxChars: Int = 2800): String {
        val previous = turns.dropLast(1)
        if (previous.isEmpty()) return ""
        val selected = mutableListOf<String>()
        var used = 0
        for (line in previous.asReversed()) {
            val cost = line.length + 1
            if (selected.isNotEmpty() && used + cost > maxChars) break
            selected.add(0, line)
            used += cost
        }
        return selected.joinToString("\n")
    }

    private fun record(speaker: String, utterance: String) {
        turns.add("$speaker: ${utterance.replace('\n', ' ').take(420)}")
        while (turns.size > 24) turns.removeFirst()
    }

    companion object {
        fun needsHistory(command: String): Boolean {
            val c = normalize(command)
            if (c.isBlank()) return false
            if (c.startsWith("e ") || c.startsWith("entao ") || c.startsWith("mas ") ||
                c.startsWith("por que") || c.startsWith("porque") || c.startsWith("e depois") ||
                c.startsWith("e antes") || c.startsWith("e quando") || c.startsWith("e onde")) return true
            val refs = listOf(
                "isso", "isso ai", "esse", "essa", "esses", "essas", "aquele", "aquela",
                "ele", "ela", "eles", "elas", "dele", "dela", "deles", "delas",
                "o primeiro", "o segundo", "a primeira", "a segunda", "o ultimo", "a ultima",
                "sobre isso", "nesse caso", "nesse assunto", "continue", "continua", "mais sobre",
                "de novo", "tambem", "e amanha", "e hoje", "faz isso", "mude para", "troque para"
            )
            return refs.any { c == it || c.contains(" $it") || c.startsWith("$it ") }
        }

        /** Only an explicit goodbye closes the continuous session. */
        fun isGoodbye(command: String): Boolean {
            val c = stripAssistant(normalize(command))
            return c in setOf(
                "tchau",
                "ate mais",
                "pode descansar",
                "pode descansa",
                "voce pode descansar",
                "pode descansar por agora",
                "pode descansar por enquanto",
                "pode ir descansar",
                "vai descansar",
                "va descansar",
                "pare de ouvir",
                "pode parar de ouvir",
                "encerrar conversa",
                "encerre a conversa",
                "encerra a conversa",
                "pode parar por agora",
                "pode encerrar por agora"
            )
        }

        private fun stripAssistant(value: String): String = value
            .removePrefix("gama ")
            .removeSuffix(" gama")
            .trim()

        private fun normalize(command: String): String =
            Normalizer.normalize(command.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
