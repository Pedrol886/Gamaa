package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Stops an unresolved device action from falling through to the conversational model.
 * The model may discuss an action, but it is never the component that decides to execute it.
 */
object DeviceActionSafety {
    private val actionPrefixes = listOf(
        "abra ", "abre ", "abrir ", "inicie ", "iniciar ", "execute ", "executar ",
        "mande ", "manda ", "enviar ", "envie ", "responda ", "responder ",
        "marque ", "marcar ", "agende ", "agendar ", "adicione ", "adicionar ",
        "coloque ", "colocar ", "crie ", "criar ", "anote ", "anotar ",
        "ligue ", "ligar ", "desligue ", "desligar ", "aumente ", "aumentar ",
        "diminua ", "diminuir ", "pause ", "pausar ", "continue ", "continuar ",
        "bloqueie ", "bloquear ", "desbloqueie ", "desbloquear ", "toque ", "tocar "
    )

    private fun norm(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

    fun looksLikeUnresolvedAction(raw: String): Boolean {
        val n = norm(raw)
        return actionPrefixes.any { n.startsWith(it) }
    }
}
