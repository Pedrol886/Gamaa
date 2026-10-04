package com.gama.assistant

data class WhatsAppSendRequest(val target: String, val message: String?)

object WhatsAppCommandParser {
    private val sendVerbs = setOf("mande", "manda", "envie", "envia", "mandar", "enviar", "avise", "avisa", "diga", "diz", "fale", "fala")
    private val appWords = setOf("whatsapp", "zap", "zapzap")
    private val separators = listOf(
        " dizendo ", " falando ", " com a mensagem ", " com o texto ",
        " que ", " que diga ", " e diga ", " texto ", " mensagem ", " avisando ", " para dizer "
    )

    fun looksLikeSendCommand(raw: String): Boolean {
        val c = VoicePolicy.normalize(raw)
        val first = c.substringBefore(' ')
        if (first !in sendVerbs) return false
        if (c.contains("mensagem") || appWords.any { c.contains(it) }) return true
        return Regex(
            "^(?:mande|manda|envie|envia|avise|avisa|diga|diz|fale|fala)\\s+(?:para|pra|pro|ao|a)\\s+.{2,80}\\s+(?:que|dizendo|falando)\\s+.+$"
        ).matches(c)
    }

    fun parse(raw: String): WhatsAppSendRequest? {
        var c = VoicePolicy.normalize(raw)
        if (!looksLikeSendCommand(c)) return null
        val first = c.substringBefore(' ')
        c = c.removePrefix(first).trim()
        repeat(3) {
            c = c.removePrefix("uma mensagem ").removePrefix("um mensagem ")
                .removePrefix("mensagem ").trim()
            c = c.replaceFirst(Regex("^(?:pelo|pela|no|na|via|por)\\s+(?:whatsapp|zap|zapzap)\\s+"), "")
            c = c.removePrefix("whatsapp ").removePrefix("zapzap ").removePrefix("zap ").trim()
        }
        val recipientPrefix = listOf("para ", "pra ", "pro ", "ao ", "a ").firstOrNull { c.startsWith(it) }
            ?: return null
        val rest = c.removePrefix(recipientPrefix).trim()
        if (rest.isBlank()) return null
        var cut = -1
        var marker = ""
        for (candidate in separators) {
            val i = rest.indexOf(candidate)
            if (i > 0 && (cut < 0 || i < cut)) { cut = i; marker = candidate }
        }
        val targetRaw = if (cut > 0) rest.substring(0, cut) else rest
        val message = if (cut > 0) {
            rest.substring(cut + marker.length)
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let(GamaConversationRepair::latestClause)
        } else null
        val target = cleanTarget(targetRaw)
        if (target.isBlank()) return null
        return WhatsAppSendRequest(target.take(80), message?.take(1200))
    }

    fun cleanTarget(raw: String): String {
        var t = VoicePolicy.normalize(raw).trim()
        val relations = listOf(
            "meu amigo ", "minha amiga ", "meu contato ", "minha contato ",
            "meu irmao ", "minha irma ", "meu pai ", "minha mae "
        )
        relations.firstOrNull { t.startsWith(it) }?.let { t = t.removePrefix(it).trim() }
        return t
    }

    fun followUpBody(raw: String): String {
        var c = VoicePolicy.normalize(raw).trim()
        val prefixes = listOf(
            "diga que ", "diz que ", "fala que ", "fale que ", "avisa que ", "avise que ",
            "escreva que ", "escreve que ", "a mensagem e ", "mensagem e ", "mande que "
        )
        prefixes.firstOrNull { c.startsWith(it) }?.let { c = c.removePrefix(it).trim() }
        return GamaConversationRepair.latestClause(c).take(1200)
    }
}
