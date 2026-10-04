package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

object GamaFaceCommandPolicy {
    fun normalize(value: String): String =
        Normalizer.normalize(
            value.lowercase(Locale.ROOT),
            Normalizer.Form.NFD,
        )
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-z0-9 ]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun isUnlockCommand(raw: String): Boolean {
        val c = normalize(raw)
        return listOf(
            "desbloqueie o celular",
            "desbloquear o celular",
            "desbloqueie meu celular",
            "desbloquear meu celular",
            "destrave o celular",
            "destravar o celular",
            "destrave meu celular",
            "destravar meu celular",
        ).any { it in c }
    }

    fun isEnrollmentCommand(raw: String): Boolean {
        val c = normalize(raw)
        return listOf(
            "cadastrar meu rosto",
            "cadastre meu rosto",
            "configurar meu rosto",
            "configure meu rosto",
            "configurar reconhecimento facial",
            "cadastrar reconhecimento facial",
            "registrar meu rosto",
            "registre meu rosto",
        ).any { it in c }
    }

    fun isSensitiveCommand(raw: String): Boolean {
        val c = normalize(raw)
        return listOf(
            "mensagem",
            "mensagens",
            "whatsapp",
            "zap",
            "notificacao",
            "notificacoes",
            "agenda",
            "calendario",
            "compromisso",
            "compromissos",
            "contato",
            "contatos",
            "leia minha tela",
            "ler minha tela",
            "resuma minha tela",
            "resumir minha tela",
            "pesquise isso na tela",
            "pesquise o que esta na tela",
            "o que esta na minha tela",
        ).any { it in c }
    }
}
