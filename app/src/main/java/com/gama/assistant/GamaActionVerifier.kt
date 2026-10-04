package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative outcome classifier used between steps of an explicit plan.
 * It never turns a vague model sentence into proof that an Android action happened.
 */
object GamaActionVerifier {
    enum class Outcome { CONFIRMED, FAILED, UNKNOWN }

    private val failureSignals = listOf(
        "nao consegui", "nao encontrei", "nao posso", "nao foi possivel",
        "falhou", "falha", "erro interno", "tente novamente", "tente de novo",
        "desbloqueie o android", "ative gama contexto da tela", "ative o gama contexto",
        "preciso confirmar", "nao entendi", "demorou demais", "indisponivel",
        "nao confirmou", "nao tenho permissao", "sem permissao", "nao autorizado"
    )

    private val confirmedSignals = listOf(
        "abri ", "aberto e confirmado", "toquei em ", "texto preenchido", "confirmei a mudanca",
        "voltando", "indo para a tela inicial", "abrindo os aplicativos recentes",
        "abrindo as notificacoes", "abrindo as configuracoes rapidas", "bloqueando a tela",
        "mensagem enviada", "enviei a mensagem", "mensagem foi enviada", "agendado",
        "marcado na agenda", "adicionei a agenda", "anotado", "timer configurado",
        "alarme configurado", "abri a rota", "abri resultados atuais", "na tela esta escrito",
        "na tela aparece", "controle do android ativado"
    )

    fun assess(reply: String): Outcome {
        val n = normalize(reply)
        if (n.isBlank()) return Outcome.UNKNOWN
        if (failureSignals.any { it in n }) return Outcome.FAILED
        if (confirmedSignals.any { it in n }) return Outcome.CONFIRMED
        return Outcome.UNKNOWN
    }

    /**
     * A plan must stop on any explicit failure. Informational steps may continue
     * when the reply is neutral, but they are never rewritten as a fake success.
     */
    fun mayContinuePlan(reply: String): Boolean = assess(reply) != Outcome.FAILED

    fun isVerifiedSuccess(reply: String): Boolean = assess(reply) == Outcome.CONFIRMED

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
