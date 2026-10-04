package com.gama.assistant

import android.content.Context

/**
 * Conversational coordinator. This layer never executes arbitrary model code.
 * It only dispatches actions present in GamaToolExecutor's allow-list.
 */
object GamaSuperAssistantCore {
    fun handle(
        context: Context,
        raw: String,
        callback: (String) -> Unit,
    ): Boolean {
        if (raw.isBlank()) return false
        GamaTaskMemory.touch(context)

        GamaAdaptiveMemory.observe(context, raw)
        GamaAdaptiveMemory.replyForQuery(context, raw)?.let { reply ->
            callback(reply)
            return true
        }
        GamaContinuityMemory.replyForQuery(context, raw)?.let { reply ->
            callback(reply)
            return true
        }
        GamaScreenMemory.replyForQuery(context, raw)?.let { reply ->
            callback(reply)
            return true
        }

        if (SessionExitPolicy.shouldEnd(raw)) {
            GamaPendingProposalStore.clear(context)
            GamaTaskMemory.clearSession(context)
            return false
        }

        val pending = GamaPendingProposalStore.load(context)
        if (pending != null) {
            when (GamaConversationLanguage.confirmation(raw)) {
                GamaConversationLanguage.Confirmation.YES -> {
                    GamaPendingProposalStore.clear(context)
                    GamaToolExecutor.executeAsync(context, pending, callback)
                    return true
                }

                GamaConversationLanguage.Confirmation.NO -> {
                    GamaPendingProposalStore.clear(context)
                    callback("Tudo bem. Não vou executar essa ação.")
                    return true
                }

                GamaConversationLanguage.Confirmation.UNKNOWN -> {
                    if (pending.tool == "calendar_complete") {
                        GamaPendingProposalStore.clear(context)
                        val combined = "marque ${pending.payload} na agenda ${raw.trim()}"
                        val result = GamaCalendarManager.handle(context, combined)
                            ?: "Não consegui entender o dia e o horário. Diga, por exemplo, amanhã às 15 horas."
                        GamaTaskMemory.rememberResult(context, result)
                        callback(result)
                        return true
                    }
                }
            }
        }

        // GAMA86_NATURAL_CONVERSATION
        GamaSocialConversation.reply(raw)?.let { socialReply ->
            callback(socialReply)
            return true
        }

        GamaWhatsAppSendParser.parse(raw)?.let { request ->
            val proposal = GamaPendingProposalStore.Proposal(
                tool = "whatsapp_send",
                payload = request.toPayload(),
                prompt = request.confirmation(),
            )
            GamaPendingProposalStore.save(context, proposal)
            GamaTaskMemory.rememberGoal(context, "Enviar mensagem para ${request.recipient}")
            callback(proposal.prompt)
            return true
        }

        if (GamaConversationLanguage.looksLikeContextQuestion(raw)) {
            callback(
                GamaTaskMemory.summary(context)
                    ?: "Ainda não tenho uma tarefa ativa para retomar."
            )
            return true
        }

        val normalized = GamaConversationLanguage.normalize(raw)

        if (
            listOf(
                "leia minha tela",
                "leia essa tela",
                "o que tem na tela",
                "o que esta na tela",
                "me diga o que esta na tela",
            ).any { it in normalized }
        ) {
            val snapshot = GamaScreenContextService.snapshotNow()
            if (snapshot == null) {
                GamaScreenContextService.openSettings(context)
                callback(
                    "Para eu ler a tela, ative o serviço Gama Contexto da Tela nas configurações de acessibilidade. Eu abri essa tela para você."
                )
            } else {
                callback(snapshot.localSummary())
            }
            return true
        }

        if (
            listOf(
                "resuma essa tela",
                "resuma minha tela",
                "resuma o que esta na tela",
            ).any { it in normalized }
        ) {
            val snapshot = GamaScreenContextService.snapshotNow()
            if (snapshot == null) {
                GamaScreenContextService.openSettings(context)
                callback(
                    "Ative o Gama Contexto da Tela na acessibilidade para eu conseguir resumir o que está visível."
                )
            } else {
                callback(snapshot.localSummary())
            }
            return true
        }

        if (
            listOf(
                "pesquise isso da tela",
                "pesquise isso que esta na tela",
                "pesquise o que esta na tela",
                "procure isso da tela",
            ).any { it in normalized }
        ) {
            val snapshot = GamaScreenContextService.snapshotNow()
            if (snapshot == null) {
                GamaScreenContextService.openSettings(context)
                callback(
                    "Ative o Gama Contexto da Tela na acessibilidade. Depois eu consigo usar o texto visível numa pesquisa quando você pedir."
                )
            } else {
                GamaToolExecutor.executeAsync(
                    context,
                    GamaPendingProposalStore.Proposal(
                        tool = "screen_research",
                        payload = "",
                        prompt = "",
                    ),
                    callback,
                )
            }
            return true
        }

        val suggestion = GamaProposalEngine.suggest(
            raw,
            hasScreenContext = GamaScreenContextService.isConnected(),
        )
        if (suggestion != null) {
            val proposal = GamaPendingProposalStore.Proposal(
                tool = suggestion.tool,
                payload = suggestion.payload,
                prompt = suggestion.prompt,
            )
            GamaPendingProposalStore.save(context, proposal)
            GamaTaskMemory.rememberGoal(context, raw)
            callback(proposal.prompt)
            return true
        }

        return false
    }
}
