package com.gama.assistant

/** Interpreta confirmações curtas somente para ações locais permitidas. */
object ActionFollowUp {
    private val confirmations = setOf(
        "sim", "confirmo", "isso", "isso mesmo", "pode", "pode sim",
        "pode executar", "pode executar isso", "execute isso", "execute",
        "pode fazer", "pode fazer isso", "faca isso", "faz isso",
        "sim pode", "pode prosseguir", "prossiga", "prossiga com isso"
    )

    private val rejections = setOf(
        "nao", "não", "cancele", "cancelar", "deixa", "deixa pra la",
        "deixa para la", "esquece", "nao execute", "não execute"
    )

    fun isConfirmation(raw: String): Boolean =
        VoicePolicy.normalize(raw) in confirmations

    fun isRejection(raw: String): Boolean =
        VoicePolicy.normalize(raw) in rejections

    fun suggestedCommand(reply: String): String? {
        val c = VoicePolicy.normalize(reply)
        if (c.isBlank()) return null

        fun has(vararg parts: String): Boolean = parts.all { c.contains(it) }

        return when {
            has("posso", "bloquear", "tela") || has("sugiro", "bloquear", "tela") ->
                "bloquear tela"

            has("posso", "ligar", "lanterna") || has("sugiro", "ligar", "lanterna") ->
                "ligar lanterna"

            has("posso", "desligar", "lanterna") || has("sugiro", "desligar", "lanterna") ->
                "desligar lanterna"

            has("posso", "aumentar", "volume") || has("sugiro", "aumentar", "volume") ->
                "aumentar volume"

            (has("posso", "diminuir", "volume") || has("posso", "abaixar", "volume") ||
                has("sugiro", "diminuir", "volume")) ->
                "diminuir volume"

            has("posso", "silenciar", "volume") || has("sugiro", "silenciar", "volume") ->
                "silenciar volume"

            has("posso", "pausar") || has("sugiro", "pausar") ->
                "pausar"

            has("posso", "continuar") || has("posso", "retomar") ||
                has("sugiro", "continuar") ->
                "continuar"

            c.contains("posso abrir as configuracoes") ||
                c.contains("posso abrir os ajustes") ||
                c.contains("sugiro abrir as configuracoes") ||
                c.contains("sugiro abrir os ajustes") ->
                "abrir configuracoes"

            c.contains("posso abrir a camera") || c.contains("sugiro abrir a camera") ->
                "abrir camera"

            c.contains("posso ir para a tela inicial") ||
                c.contains("posso voltar para a tela inicial") ->
                "tela inicial"

            c.contains("posso pesquisar na wikipedia ") -> {
                val q = c.substringAfter("posso pesquisar na wikipedia ").trim().take(120)
                if (q.isBlank()) null else "pesquise na wikipedia $q"
            }

            c.contains("posso procurar na wikipedia ") -> {
                val q = c.substringAfter("posso procurar na wikipedia ").trim().take(120)
                if (q.isBlank()) null else "pesquise na wikipedia $q"
            }

            c.contains("posso abrir ") -> {
                val q = c.substringAfter("posso abrir ")
                    .substringBefore(" para ")
                    .substringBefore(" se ")
                    .substringBefore(" quando ")
                    .trim()
                    .removePrefix("o ")
                    .removePrefix("a ")
                    .removePrefix("app ")
                    .removePrefix("aplicativo ")
                    .take(60)
                if (q.length < 2 || q in setOf("isso", "essa opcao", "essa ideia")) null
                else "abrir $q"
            }

            else -> null
        }
    }
}
