package com.gama.assistant

import android.content.Context

/**
 * Central conversational policy used by GamaService.
 * It does not bypass Android security and never invents tool success.
 */
object ZevronJarvisCore {
    fun enrichBrainContext(context: Context, transientContext: String?): String? =
        listOfNotNull(
            transientContext?.takeIf { it.isNotBlank() },
            GamaTaskMemory.summary(context),
            GamaContextFusion.snapshot(context),
            GamaScreenMemory.contextSummary(context),
            GamaContinuityMemory.contextSummary(context),
            GamaReferenceMemory.contextSummary(context),
            ZevronMemoryStore.brainContext(context),
            GamaAdaptiveMemory.contextSummary(context),
        )
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(BrainProcessProtocol.MAX_HISTORY_CHARS)
            .takeIf { it.isNotBlank() }

    fun brainInstruction(command: String): String =
        "Você é Gama, um copiloto operacional de voz no Android. " +
            "Converse em português brasileiro com respostas curtas, fluidas e contextuais, sem soar como menu de aplicativo. " +
            "Acompanhe a missão atual, o histórico recente e os eventos do aparelho. Entenda pronomes, continuações, interrupções e correções naturais. " +
            "Se o usuário se corrigir com expressões como 'aliás' ou 'na verdade', a intenção mais recente prevalece. " +
            "Quando uma tarefa estiver em andamento, responda levando em conta a etapa atual em vez de começar do zero. " +
            "Não peça ao usuário para repetir dados que já aparecem no contexto. " +
            "Não diga que abriu, enviou, marcou, alterou ou executou algo se uma ferramenta Android não confirmou isso. " +
            "Não invente acesso à tela, mensagens, arquivos, localização ou Internet. " +
            "Quando faltar um dado realmente essencial, faça uma única pergunta curta. " +
            "Não mencione modelo .task, processo, IPC, cache ou detalhes internos do aplicativo. " +
            "Não recite limitações nem capacidades sem necessidade. Não repita o nome do usuário nem tratamentos formais em toda resposta. " +
            "Para conhecimento geral, responda com o que sabe. Informação atual só deve ser tratada como atual quando o sistema fornecer uma fonte atual. " +
            "Pedido: ${command.trim()}"
}
