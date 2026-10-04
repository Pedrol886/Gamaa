package com.gama.assistant

object GamaProposalEngine {
    data class Suggestion(
        val tool: String,
        val payload: String,
        val prompt: String,
    )

    fun suggest(raw: String, hasScreenContext: Boolean): Suggestion? {
        val c = GamaConversationLanguage.normalize(raw)
            .removePrefix("gama ")
            .trim()

        Regex("""^(?:eu )?preciso (?:me )?lembrar de (.+)$""")
            .find(c)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { subject ->
                return Suggestion(
                    tool = "calendar_prepare",
                    payload = subject,
                    prompt = "Posso transformar isso em um compromisso na sua agenda. Quer que eu faça?",
                )
            }

        Regex("""^(?:eu )?(?:quero|estou pensando em) comprar (.+)$""")
            .find(c)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { product ->
                return Suggestion(
                    tool = "price_search",
                    payload = product,
                    prompt = "Posso pesquisar preços atuais de $product na Internet. Quer que eu pesquise?",
                )
            }

        Regex("""^(?:eu )?(?:preciso|quero|vou) (?:ir|chegar) (?:para|a|em) (.+)$""")
            .find(c)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { destination ->
                return Suggestion(
                    tool = "route",
                    payload = destination,
                    prompt = "Posso abrir uma rota para $destination. Quer que eu faça?",
                )
            }

        if (
            hasScreenContext &&
            listOf(
                "nao sei o que e isso",
                "o que e isso",
                "quero saber mais sobre isso",
                "descubra o que e isso",
            ).any { it in c }
        ) {
            return Suggestion(
                tool = "screen_research",
                payload = "",
                prompt = "Posso usar o que está visível na tela para pesquisar isso. Quer que eu faça?",
            )
        }

        Regex("""^(?:eu )?(?:preciso saber|quero saber mais|quero entender) (?:sobre )?(.+)$""")
            .find(c)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.length >= 3 }
            ?.let { topic ->
                return Suggestion(
                    tool = "research",
                    payload = topic,
                    prompt = "Posso pesquisar informações atuais sobre $topic. Quer que eu faça?",
                )
            }

        return null
    }
}
