package com.gama.assistant

/** Pure response rules. They do not execute Android actions. */
object GamaPersona {
    val titles = setOf("Senhor", "Senhora")

    /** Legacy explicit-address helper. */
    fun address(reply: String, title: String): String {
        val text = reply.trim()
        if (text.isEmpty()) return text
        val chosen = title.takeIf { it in titles } ?: "Senhor"
        if (text.startsWith("$chosen,", ignoreCase = true) ||
            text.startsWith("$chosen.", ignoreCase = true)) return text
        return "$chosen, $text"
    }

    /**
     * Normal conversation must not prepend the owner's name or title to every
     * sentence. We only clean duplicated assistant/honorific prefixes here.
     */
    fun address(reply: String, title: String, name: String?): String = cleanNatural(reply)

    /** Explicit formal address for privacy/authentication replies only. */
    fun formalAddress(reply: String, title: String, name: String?): String {
        val text = cleanNatural(reply)
        if (text.isBlank()) return text
        val chosen = title.takeIf { it in titles } ?: "Senhor"
        val cleanName = name?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }
        val prefix = if (cleanName == null) chosen else "$chosen $cleanName"
        return if (text.startsWith(prefix, ignoreCase = true)) text else "$prefix, $text"
    }

    private fun cleanNatural(reply: String): String {
        var text = reply.trim()
        if (text.isEmpty()) return text

        text = text.replaceFirst(
            Regex("(?i)^(?:(?:gama|gamma)\\s*[,.:;-]?\\s*)+"),
            ""
        ).trim()

        // Prevent stacked phrases such as "Senhor Pedro, sim senhor..." from
        // surviving old model prompts. Do not add a new honorific afterwards.
        text = text.replaceFirst(
            Regex("(?i)^(?:sim\\s+)?(?:senhor|senhora|chefe)(?:\\s+[\\p{L}][\\p{L} -]{0,50})?\\s*[,.:;-]?\\s*"),
            ""
        ).trim()
        text = text.replace(
            Regex("(?i),?\\s+(?:senhor|senhora|chefe)[.!?]?$"),
            "."
        ).trim()
        return text
    }

    fun batteryAdvice(level: Int): String = when (level) {
        in 0..10 -> "Sugiro conectar o carregador e pausar a escuta contínua para economizar energia."
        in 11..20 -> "Sugiro ativar a economia de energia nos ajustes se precisar preservar a bateria."
        else -> ""
    }

    fun prompt(question: String, history: String, title: String, facts: List<String>, focus: Boolean): String {
        val chosen = title.takeIf { it in titles } ?: "Senhor"
        val profile = if (facts.isEmpty()) {
            "Nenhuma preferência pessoal explicitamente memorizada."
        } else {
            facts.takeLast(6).joinToString("\n") { "- ${it.take(160).replace('\n', ' ')}" }
        }
        val focusText = if (focus) {
            "Modo foco local ativado: seja curto, preciso e sem perguntas dispensáveis."
        } else {
            "Humor seco e discreto somente em situações leves. Nunca seja rude ou invasivo."
        }
        return """
            Você é Gama, um assistente pessoal Android em português do Brasil inspirado na fluidez de um mordomo tecnológico de ficção, mas limitado às capacidades reais do aparelho.
            O tratamento configurado do usuário é $chosen. Use tratamento formal apenas quando soar natural ou quando privacidade/segurança exigir; não repita nome ou título em toda resposta.
            Se perguntarem sua origem, seu criador público se chama ${GamaCreator.PUBLIC_NAME}.
            Nunca presuma que a voz autentica o proprietário. Dados privados dependem da autenticação oficial do Android quando necessário.
            Responda diretamente e de forma natural. Para perguntas simples, seja breve. Para explicações, use detalhe suficiente sem transformar tudo em uma única frase.
            $focusText
            Você conversa continuamente. Referências claras como "isso", "ele", "e amanhã?", "faz o mesmo" e "e depois?" devem usar o contexto recente.
            Quando houver ambiguidade real, peça uma única clarificação objetiva em vez de inventar.
            Não finja ter executado ações. Ferramentas Android, calendário, WhatsApp, tela, Internet e outros recursos são executados fora deste modelo e só podem ser confirmados quando o sistema os verificou.
            Não invente clima, agenda, mensagens, localização, bateria, compromissos, resultados recentes ou fatos atuais.
            Se a pergunta exigir informação atual e nenhuma fonte atual tiver sido fornecida, diga de forma curta que precisa consultar uma fonte atual.
            Nunca exponha ao usuário detalhes internos como .task, IPC, processo isolado, cache, stack trace, PID ou nomes de módulos.
            Memória pessoal: use somente fatos explicitamente autorizados abaixo.
            Fatos locais autorizados:
            $profile
            Contexto recente da sessão:
            ${history.take(3600)}
            Mensagem atual do usuário:
            ${question.take(1200)}
        """.trimIndent()
    }
}

/** Decide when a public question needs a fresh source instead of the offline model. */
object FreshnessPolicy {
    private val offices = listOf(
        "presidente", "vice presidente", "governador", "prefeito", "primeiro ministro",
        "presidente da camara", "presidente do senado", "presidente do stf", "ministro"
    )
    private val currentWords = listOf(
        "atual", "atualmente", "agora", "hoje", "neste momento", "quem ocupa", "quem governa"
    )
    private val generalFreshWords = listOf(
        "mais recente", "mais recentes", "ultima noticia", "ultimas noticias",
        "novidade de hoje", "novidades de hoje", "acabou de", "neste momento"
    )

    fun requiresFreshSource(command: String): Boolean {
        val c = VoicePolicy.normalize(command)
        if (c.isBlank()) return false
        if (generalFreshWords.any { it in c }) return true
        val historical = Regex("\\b(?:18|19|20)\\d{2}\\b").containsMatchIn(c) && !c.contains("atual")
        if (historical || c.contains("quem foi ") || c.contains("era o presidente")) return false
        val office = offices.any { c.contains(it) }
        if (!office) return false
        val directQuestion = c.startsWith("quem e ") || c.startsWith("quem e o ") ||
            c.startsWith("quem e a ") || c.startsWith("qual e ") || c.startsWith("qual e o ") ||
            c.startsWith("qual e a ") || c.startsWith("qual o ") || c.startsWith("qual a ")
        return currentWords.any { c.contains(it) } || directQuestion
    }

    fun researchQuery(command: String): String {
        val c = VoicePolicy.normalize(command)
        return when {
            c.contains("vice presidente") && c.contains("brasil") -> "Vice-presidente do Brasil"
            c.contains("presidente") && c.contains("brasil") -> "Presidente do Brasil"
            c.contains("presidente") && (c.contains("estados unidos") || c.contains("eua")) -> "Presidente dos Estados Unidos"
            else -> c
        }
    }
}
