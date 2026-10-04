package com.gama.assistant

import android.content.Context
import java.text.Normalizer
import java.util.Locale

object GamaOnlineRouter {
    enum class Mode { NONE, NEWS, WEATHER, CURRENCY, RESEARCH, LIVE_SEARCH, PAGE_SUMMARY, OPEN_LINK, ROUTE, PRICE, SPORTS, ENTERTAINMENT, WEB_SEARCH }

    fun classify(raw: String): Mode {
        val c = normalize(raw)
        if (c.isBlank() || isPrivate(c)) return Mode.NONE
        val hasUrl = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (hasUrl && any(c, "resuma", "resumir", "resumo", "explique esse link", "o que diz esse link")) return Mode.PAGE_SUMMARY
        if (hasUrl && any(c, "abra", "abrir", "acesse", "entre")) return Mode.OPEN_LINK
        if (any(c, "noticia", "noticias", "manchete", "manchetes", "aconteceu hoje", "ultimas noticias")) return Mode.NEWS
        if (any(c, "clima", "previsao do tempo", "tempo em", "vai chover", "temperatura em")) return Mode.WEATHER
        if (any(c, "cotacao", "dolar", "dolares", "euro", "euros", "libra", "iene", "converter moeda")) return Mode.CURRENCY
        if (any(c, "rota para", "como chegar", "transito para", "ir para", "abrir maps", "abra o maps")) return Mode.ROUTE
        if (any(c, "quanto custa", "preco de", "precos de", "compare precos", "comparar precos")) return Mode.PRICE
        if (any(c, "placar", "brasileirao", "libertadores", "champions", "formula 1", "resultado do jogo", "proximo jogo")) return Mode.SPORTS
        if (any(c, "estreia do filme", "estreia da serie", "lancamento do filme", "lancamento da serie", "cinema hoje")) return Mode.ENTERTAINMENT
        if (any(c, "eleicao", "eleicoes", "votacao", "horario de votacao", "urna", "urnas")) return Mode.LIVE_SEARCH
        if (any(c, "que horas comeca", "que horas inicia", "quando comeca", "quando inicia", "quando abre", "quando fecha") &&
            any(c, "hoje", "amanha", "domingo", "segunda", "terca", "quarta", "quinta", "sexta", "sabado")) return Mode.LIVE_SEARCH
        if (any(c, "pesquise na internet", "procure na internet", "pesquise sobre", "pesquisar sobre", "pesquisa sobre")) return Mode.RESEARCH
        if (any(c, "abra o site", "abrir o site", "pesquise no google", "buscar no google", "busque no google")) return Mode.WEB_SEARCH
        return Mode.NONE
    }

    fun handleAsync(context: Context, raw: String, callback: (String) -> Unit): Boolean {
        val mode = classify(raw)
        if (mode == Mode.NONE) return false
        when (mode) {
            Mode.OPEN_LINK -> callback(if (extractUrl(raw)?.let { GamaInternetHub.openUrl(context, it) } == true) "Abri o link." else "Não consegui abrir esse link.")
            Mode.ROUTE -> {
                val destination = after(raw, "rota para", "como chegar em", "como chegar a", "transito para", "ir para")
                callback(if (destination.isBlank()) "Diga para onde você quer ir." else if (GamaInternetHub.openRoute(context, destination)) "Abri a rota para $destination." else "Não consegui abrir a rota agora.")
            }
            Mode.PRICE -> {
                val product = after(raw, "quanto custa", "preco de", "precos de", "compare precos de", "comparar precos de")
                callback(if (product.isBlank()) "Diga qual produto você quer pesquisar." else if (GamaInternetHub.openPriceSearch(context, product)) "Abri resultados atuais de preço para $product. Confira a loja e o valor antes de comprar." else "Não consegui abrir a pesquisa de preços agora.")
            }
            Mode.WEB_SEARCH -> {
                val q = after(raw, "abra o site", "abrir o site", "pesquise no google", "buscar no google", "busque no google")
                callback(if (GamaInternetHub.openSearch(context, q)) "Abri a pesquisa na Internet." else "Não consegui abrir a pesquisa agora.")
            }
            else -> Thread({
                val answer = runCatching { execute(context, raw, mode) }.getOrElse { "A consulta online falhou, mas continuo funcionando normalmente." }
                callback(answer)
            }, "gama-internet-${mode.name.lowercase(Locale.ROOT)}").apply { isDaemon = true; start() }
        }
        return true
    }

    private fun execute(context: Context, raw: String, mode: Mode): String = when (mode) {
        Mode.NEWS -> {
            val topic = newsTopic(raw)
            val headlines = if (topic.isBlank()) GamaInternetHub.topNews(context) else GamaInternetHub.newsAbout(topic)
            if (headlines.isEmpty()) "Não consegui atualizar as notícias pela Internet agora."
            else (if (topic.isBlank()) "As principais notícias que encontrei agora são: " else "As notícias mais recentes que encontrei sobre $topic são: ") + headlines.take(6).joinToString("; ") + "."
        }
        Mode.WEATHER -> GamaInternetHub.weather(weatherPlace(raw))
        Mode.CURRENCY -> GamaInternetHub.currency(raw)
        Mode.RESEARCH -> GamaInternetHub.liveSearch(after(raw, "pesquise na internet", "procure na internet", "pesquise sobre", "pesquisar sobre", "pesquisa sobre"))
        Mode.LIVE_SEARCH -> GamaInternetHub.liveSearch(liveQuery(raw))
        Mode.PAGE_SUMMARY -> extractUrl(raw)?.let { GamaInternetHub.summarizeUrl(it) } ?: "Não encontrei o link na sua frase."
        Mode.SPORTS -> {
            val h = GamaInternetHub.newsAbout(normalize(raw) + " esporte")
            if (h.isEmpty()) "Não consegui atualizar esse resultado esportivo agora." else "Encontrei estas atualizações esportivas: ${h.take(6).joinToString("; ")}."
        }
        Mode.ENTERTAINMENT -> {
            val q = normalize(raw)
            val h = GamaInternetHub.newsAbout(q)
            if (h.isEmpty()) GamaInternetHub.research(q) else "Encontrei estas informações recentes: ${h.take(5).joinToString("; ")}."
        }
        else -> "Não consegui concluir essa consulta online."
    }

    private fun isPrivate(c: String): Boolean = any(c,
        "agenda", "calendario", "compromisso", "evento", "mensagem", "mensagens", "whatsapp", "zap",
        "notificacao", "notificacoes", "contato", "contatos", "bateria", "alarme", "timer", "automatizacao",
        "automacao", "todo dia", "toda semana", "abra o aplicativo", "abrir o aplicativo", "abra o app", "abrir o app",
        "bom dia", "boa noite", "vou dormir", "pode descansar")

    private fun newsTopic(raw: String): String {
        val c = normalize(raw)
        return listOf(Regex("""noticias?\s+(?:sobre|de|do|da)\s+(.+)"""), Regex("""manchetes?\s+(?:sobre|de|do|da)\s+(.+)"""))
            .firstNotNullOfOrNull { it.find(c)?.groupValues?.getOrNull(1) }?.trim().orEmpty()
    }

    private fun weatherPlace(raw: String): String {
        val c = normalize(raw)
        return listOf(Regex("""(?:clima|tempo|temperatura)\s+em\s+(.+)"""), Regex("""previsao(?:\s+do\s+tempo)?\s+(?:para|em)\s+(.+)"""), Regex("""vai\s+chover\s+em\s+(.+)"""))
            .firstNotNullOfOrNull { it.find(c)?.groupValues?.getOrNull(1) }
            ?.replace(Regex("""\b(hoje|amanha|agora)\b"""), " ")?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
    }

    private fun liveQuery(raw: String): String {
        val c = normalize(raw).removePrefix("gama ").trim()
        return if (any(c, "eleicao", "eleicoes", "votacao", "urna", "urnas")) {
            "$c site:tse.jus.br"
        } else c
    }

    private fun after(raw: String, vararg starters: String): String {
        var c = normalize(raw).removePrefix("gama ").trim()
        for (s in starters) if (c.startsWith(s)) return c.removePrefix(s).trim()
        return c
    }

    private fun extractUrl(raw: String): String? = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE).find(raw)
        ?.value?.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')

    private fun any(c: String, vararg terms: String): Boolean = terms.any { it in c }
    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").replace(Regex("""[^a-z0-9:/?&.=_%+\- ]+"""), " ")
        .replace(Regex("""\s+"""), " ").trim()
}
