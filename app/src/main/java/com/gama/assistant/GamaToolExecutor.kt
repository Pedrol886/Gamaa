package com.gama.assistant

import android.content.Context

object GamaToolExecutor {
    fun executeAsync(
        context: Context,
        proposal: GamaPendingProposalStore.Proposal,
        callback: (String) -> Unit,
    ) {
        when (proposal.tool) {
            "calendar_prepare" -> {
                GamaTaskMemory.rememberGoal(
                    context,
                    "Adicionar ${proposal.payload} à agenda",
                )
                GamaPendingProposalStore.save(
                    context,
                    GamaPendingProposalStore.Proposal(
                        tool = "calendar_complete",
                        payload = proposal.payload,
                        prompt = "Em que dia e horário você quer marcar ${proposal.payload}?",
                    ),
                )
                callback(
                    "Certo. Em que dia e horário você quer marcar ${proposal.payload}?"
                )
            }

            "price_search" -> {
                GamaTaskMemory.rememberGoal(
                    context,
                    "Pesquisar preço de ${proposal.payload}",
                )
                val opened = GamaInternetHub.openPriceSearch(
                    context,
                    proposal.payload,
                )
                val result = if (opened) {
                    "Abri resultados atuais de preço para ${proposal.payload}. Confira o vendedor e o valor antes de comprar."
                } else {
                    "Não consegui abrir a pesquisa de preços agora."
                }
                GamaTaskMemory.rememberResult(context, result)
                callback(result)
            }

            "route" -> {
                GamaTaskMemory.rememberGoal(
                    context,
                    "Abrir rota para ${proposal.payload}",
                )
                val opened = GamaInternetHub.openRoute(
                    context,
                    proposal.payload,
                )
                val result = if (opened) {
                    "Abri a rota para ${proposal.payload}."
                } else {
                    "Não consegui abrir a rota agora."
                }
                GamaTaskMemory.rememberResult(context, result)
                callback(result)
            }

            "research" -> runNetwork(
                context,
                "Pesquisar ${proposal.payload}",
                callback,
            ) {
                GamaInternetHub.research(proposal.payload)
            }

            "screen_research" -> {
                val snapshot = GamaScreenContextService.snapshotNow()
                val query = snapshot?.searchQuery().orEmpty()
                if (query.isBlank()) {
                    callback("Não consegui extrair texto suficiente da tela para pesquisar.")
                } else {
                    runNetwork(
                        context,
                        "Pesquisar o assunto visível na tela",
                        callback,
                    ) {
                        GamaInternetHub.research(query)
                    }
                }
            }

            "whatsapp_send" -> {
                val request = GamaWhatsAppSendParser.fromPayload(proposal.payload)
                if (request == null) {
                    callback("Não consegui reconstruir a mensagem que você confirmou.")
                } else {
                    GamaWhatsAppAutomation.start(context, request) { result ->
                        GamaTaskMemory.rememberResult(context, result)
                        callback(result)
                    }
                }
            }

            else -> callback("Essa ação ainda não tem um executor seguro disponível.")
        }
    }

    private fun runNetwork(
        context: Context,
        goal: String,
        callback: (String) -> Unit,
        block: () -> String,
    ) {
        GamaTaskMemory.rememberGoal(context, goal)
        Thread({
            var result: String? = null
            var lastFailure: Throwable? = null
            for (attempt in 0..1) {
                try {
                    val candidate = block().trim()
                    if (candidate.isNotBlank()) {
                        result = candidate
                        GamaRecoveryCoordinator.markHealthy("network-tool")
                        break
                    }
                } catch (error: Throwable) {
                    lastFailure = error
                }
                if (attempt == 0) {
                    val delay = GamaRecoveryCoordinator.nextDelayMs("network-tool").coerceAtMost(1_200L)
                    runCatching { Thread.sleep(delay) }
                }
            }
            val finalResult = result ?: if (lastFailure != null) {
                "Não consegui concluir a consulta agora. Posso tentar novamente."
            } else {
                "A consulta não retornou dados suficientes agora."
            }
            GamaTaskMemory.rememberResult(context, finalResult)
            GamaContinuityMemory.recordAction(context, goal, finalResult)
            callback(finalResult)
        }, "gama-super-tool").apply {
            isDaemon = true
            start()
        }
    }
}
