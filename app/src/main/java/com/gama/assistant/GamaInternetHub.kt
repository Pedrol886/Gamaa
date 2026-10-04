package com.gama.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import org.json.JSONObject
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fail-soft Internet layer. Only explicit public queries enter here.
 * Messages, contacts, notification bodies, calendar entries and
 * automation data stay on-device and never enter this module.
 */
object GamaInternetHub {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 6_000
    private const val MAX_TEXT_CHARS = 220_000

    fun topNews(context: Context): List<String> =
        GamaInternetNews.fetch(context).headlines.take(8)

    fun newsAbout(query: String): List<String> {
        val clean = query.trim().take(180)
        if (clean.isBlank()) return emptyList()
        val url = "https://news.google.com/rss/search?q=${enc(clean)}&hl=pt-BR&gl=BR&ceid=BR:pt-419"
        val xml = get(url, "application/rss+xml, application/xml, text/xml") ?: return emptyList()
        return parseRss(xml, 8)
    }

    fun weather(place: String): String {
        val city = place.trim().take(100)
        if (city.isBlank()) return "Diga a cidade para eu consultar a previsão do tempo."
        val geoText = get(
            "https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=1&language=pt&format=json",
            "application/json",
        ) ?: return "Não consegui acessar a previsão do tempo agora."
        val geo = runCatching { JSONObject(geoText) }.getOrNull()
            ?: return "Não consegui interpretar a localização agora."
        val results = geo.optJSONArray("results")
        if (results == null || results.length() == 0) {
            return "Não encontrei essa cidade. Tente dizer a cidade e o estado."
        }
        val first = results.optJSONObject(0) ?: return "Não encontrei essa cidade."
        val lat = first.optDouble("latitude", Double.NaN)
        val lon = first.optDouble("longitude", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite()) return "Não consegui localizar essa cidade."
        val name = first.optString("name", city)
        val admin = first.optString("admin1", "")
        val label = listOf(name, admin).filter { it.isNotBlank() }.distinct().joinToString(", ")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,precipitation,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=2"
        val forecastText = get(url, "application/json")
            ?: return "Localizei $label, mas não consegui baixar a previsão agora."
        val root = runCatching { JSONObject(forecastText) }.getOrNull()
            ?: return "Não consegui interpretar a previsão agora."
        val current = root.optJSONObject("current")
        val daily = root.optJSONObject("daily")
        val temp = current?.optDouble("temperature_2m", Double.NaN) ?: Double.NaN
        val apparent = current?.optDouble("apparent_temperature", Double.NaN) ?: Double.NaN
        val code = current?.optInt("weather_code", -1) ?: -1
        val max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
        val min = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
        val rain = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
        val parts = mutableListOf<String>()
        parts += "Em $label, ${weatherDescription(code)}"
        if (temp.isFinite()) parts += "Agora faz ${one(temp)} graus"
        if (apparent.isFinite() && temp.isFinite() && kotlin.math.abs(apparent - temp) >= 1.0) {
            parts += "A sensação é de ${one(apparent)} graus"
        }
        if (min.isFinite() && max.isFinite()) parts += "Hoje a mínima fica perto de ${one(min)} e a máxima de ${one(max)} graus"
        if (rain >= 0) parts += "A chance máxima de chuva hoje é de $rain por cento"
        return parts.joinToString(". ") + "."
    }

    fun currency(raw: String): String {
        val n = normalize(raw)
        val aliases = listOf(
            Triple("dolar", "USD", "dólares"), Triple("usd", "USD", "dólares"),
            Triple("real", "BRL", "reais"), Triple("reais", "BRL", "reais"), Triple("brl", "BRL", "reais"),
            Triple("euro", "EUR", "euros"), Triple("eur", "EUR", "euros"),
            Triple("libra", "GBP", "libras"), Triple("gbp", "GBP", "libras"),
            Triple("iene", "JPY", "ienes"), Triple("jpy", "JPY", "ienes"),
        )
        val mentions = mutableListOf<Pair<Int, Triple<String, String, String>>>()
        for (item in aliases) {
            Regex("\\b${Regex.escape(item.first)}\\b").findAll(n).forEach { mentions += it.range.first to item }
        }
        val sorted = mentions.sortedBy { it.first }
        val amount = Regex("""\b(\d+(?:[.,]\d+)?)\b""").find(n)
            ?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
        val from = when {
            sorted.size >= 2 -> sorted[0].second.second
            sorted.size == 1 && sorted[0].second.second != "BRL" -> sorted[0].second.second
            sorted.size == 1 -> "BRL"
            else -> "USD"
        }
        val to = when {
            sorted.size >= 2 -> sorted[1].second.second
            from == "BRL" -> "USD"
            else -> "BRL"
        }
        if (from == to) return "A origem e o destino são a mesma moeda."
        val text = get(
            "https://api.frankfurter.app/latest?amount=$amount&from=$from&to=$to",
            "application/json",
        ) ?: return "Não consegui consultar a cotação agora."
        val value = runCatching { JSONObject(text).optJSONObject("rates")?.optDouble(to, Double.NaN) ?: Double.NaN }
            .getOrDefault(Double.NaN)
        if (!value.isFinite()) return "A fonte de câmbio não retornou essa conversão agora."
        return "${num(amount)} ${currencyName(from)} equivalem a aproximadamente ${num(value)} ${currencyName(to)}, usando a cotação online mais recente disponível."
    }

    fun research(query: String): String {
        val clean = query.trim().take(220)
        if (clean.isBlank()) return "Diga o assunto que você quer pesquisar."
        val search = get(
            "https://pt.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(clean)}&srlimit=1&format=json&utf8=1",
            "application/json",
        ) ?: return "Não consegui pesquisar esse assunto agora."
        val root = runCatching { JSONObject(search) }.getOrNull()
            ?: return "Não consegui interpretar a pesquisa agora."
        val hits = root.optJSONObject("query")?.optJSONArray("search")
        if (hits == null || hits.length() == 0) return "Não encontrei um resultado confiável para esse assunto."
        val title = hits.optJSONObject(0)?.optString("title", "").orEmpty()
        if (title.isBlank()) return "Não encontrei um resultado confiável para esse assunto."
        val extract = get(
            "https://pt.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&titles=${enc(title)}&format=json&utf8=1",
            "application/json",
        ) ?: return "Encontrei $title, mas não consegui abrir o resumo agora."
        val pages = runCatching { JSONObject(extract).optJSONObject("query")?.optJSONObject("pages") }.getOrNull()
            ?: return "Encontrei $title, mas o resumo não estava disponível."
        val keys = pages.keys()
        var text = ""
        while (keys.hasNext()) {
            text = pages.optJSONObject(keys.next())?.optString("extract", "").orEmpty()
            if (text.isNotBlank()) break
        }
        if (text.isBlank()) return "Encontrei $title, mas não havia um resumo disponível."
        return "$title. ${sentences(text, 8, 1800)}"
    }


    fun liveSearch(query: String): String {
        val clean = query.trim().take(240)
        if (clean.isBlank()) return "Diga o que você quer consultar na Internet."

        val html = get(
            "https://html.duckduckgo.com/html/?q=${enc(clean)}",
            "text/html, application/xhtml+xml",
        )
        if (html != null) {
            val titles = Regex(
                """(?is)<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*>(.*?)</a>"""
            ).findAll(html).map { htmlText(it.groupValues[1]) }.filter { it.isNotBlank() }.take(6).toList()
            val snippets = Regex(
                """(?is)<(?:a|div)[^>]*class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div)>"""
            ).findAll(html).map { htmlText(it.groupValues[1]) }.filter { it.isNotBlank() }.take(6).toList()

            val results = titles.indices.mapNotNull { index ->
                val title = titles.getOrNull(index).orEmpty()
                val snippet = snippets.getOrNull(index).orEmpty()
                listOf(title, snippet).filter { it.isNotBlank() }.distinct().joinToString(". ").take(520)
                    .takeIf { it.isNotBlank() }
            }.distinct().take(5)

            if (results.isNotEmpty()) {
                return "Pesquisei agora na Internet. " + results.joinToString("; ") + "."
            }
        }

        val news = newsAbout(clean.removeSuffix(" site:tse.jus.br").trim())
        return if (news.isNotEmpty()) {
            "Não consegui ler os resultados gerais, mas encontrei estas fontes recentes: ${news.take(5).joinToString("; ")}."
        } else {
            "Não consegui obter uma fonte atual confiável agora. Tente novamente em instantes."
        }
    }

    fun summarizeUrl(rawUrl: String): String {
        val url = publicUrl(rawUrl) ?: return "Esse link não é um endereço público HTTP ou HTTPS válido."
        val html = get(url, "text/html, application/xhtml+xml") ?: return "Não consegui abrir essa página agora."
        val title = Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)
            ?.groupValues?.getOrNull(1)?.let(::htmlText).orEmpty()
        val desc = Regex("""(?is)<meta[^>]+(?:name|property)\s*=\s*["'](?:description|og:description)["'][^>]+content\s*=\s*["'](.*?)["'][^>]*>""")
            .find(html)?.groupValues?.getOrNull(1)?.let(::htmlText).orEmpty()
        val body = html
            .replace(Regex("""(?is)<script\b.*?</script>"""), " ")
            .replace(Regex("""(?is)<style\b.*?</style>"""), " ")
            .let(::htmlText)
        val summary = sentences(body, 8, 1800)
        val parts = listOf(title, desc, summary).filter { it.isNotBlank() }.distinct()
        return if (parts.isEmpty()) "Abri a página, mas não encontrei texto suficiente para resumir."
        else parts.joinToString(". ").take(2_400)
    }

    fun openUrl(context: Context, raw: String): Boolean {
        val url = publicUrl(raw) ?: return false
        return open(context, url)
    }

    fun openSearch(context: Context, query: String): Boolean =
        query.trim().takeIf { it.isNotBlank() }?.let { open(context, "https://www.google.com/search?q=${enc(it)}") } ?: false

    fun openPriceSearch(context: Context, query: String): Boolean =
        query.trim().takeIf { it.isNotBlank() }?.let { open(context, "https://www.google.com/search?tbm=shop&q=${enc(it)}") } ?: false

    fun openRoute(context: Context, destination: String): Boolean =
        destination.trim().takeIf { it.isNotBlank() }?.let { open(context, "https://www.google.com/maps/dir/?api=1&destination=${enc(it)}") } ?: false

    private fun open(context: Context, url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun get(url: String, accept: String): String? {
        val c = runCatching { (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Gama/6.0 Android")
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.5")
        } }.getOrNull() ?: return null
        return try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(4096)
                while (out.length < MAX_TEXT_CHARS) {
                    val r = reader.read(buffer, 0, minOf(buffer.size, MAX_TEXT_CHARS - out.length))
                    if (r <= 0) break
                    out.append(buffer, 0, r)
                }
                out.toString()
            }
        } catch (_: Exception) { null } finally { runCatching { c.disconnect() } }
    }

    private fun parseRss(xml: String, limit: Int): List<String> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val items = doc.getElementsByTagName("item")
        buildList {
            for (i in 0 until items.length) {
                val children = items.item(i).childNodes
                var title = ""
                for (j in 0 until children.length) {
                    if (children.item(j).nodeName.equals("title", true)) {
                        title = children.item(j).textContent.orEmpty(); break
                    }
                }
                val clean = title.replace(Regex("""\s+-\s+[^-]{2,80}$"""), " ").replace(Regex("""\s+"""), " ").trim()
                if (clean.isNotBlank() && clean !in this) add(clean)
                if (size >= limit) break
            }
        }
    }.getOrDefault(emptyList())

    private fun publicUrl(raw: String): String? {
        val u = runCatching { URL(raw.trim().trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')) }.getOrNull() ?: return null
        if (u.protocol != "https" && u.protocol != "http") return null
        val h = u.host.lowercase(Locale.ROOT)
        if (h.isBlank()) return null
        val blocked = h == "localhost" || h == "0.0.0.0" || h == "::1" || h.startsWith("127.") ||
            h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.") || h.endsWith(".local") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(h)
        return if (blocked) null else u.toString()
    }

    private fun htmlText(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        .toString().replace(Regex("""\s+"""), " ").trim()

    private fun sentences(value: String, count: Int, maxChars: Int): String =
        Regex("""(?<=[.!?])\s+""").split(value.replace(Regex("""\s+"""), " ").trim())
            .take(count).joinToString(" ").take(maxChars).trim()

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "o céu está limpo"; 1, 2 -> "há poucas nuvens"; 3 -> "o céu está nublado"
        45, 48 -> "há neblina"; 51, 53, 55, 56, 57 -> "há garoa"
        61, 63, 65, 66, 67 -> "está chovendo"; 80, 81, 82 -> "há pancadas de chuva"
        95, 96, 99 -> "há trovoadas"; else -> "a previsão está disponível"
    }

    private fun currencyName(code: String): String = when (code) {
        "USD" -> "dólares"; "BRL" -> "reais"; "EUR" -> "euros"; "GBP" -> "libras"; "JPY" -> "ienes"; else -> code
    }

    private fun num(value: Double): String = if (kotlin.math.abs(value - value.toLong()) < 0.000001) value.toLong().toString()
        else String.format(Locale("pt", "BR"), "%.2f", value)

    private fun one(value: Double): String = String.format(Locale("pt", "BR"), "%.1f", value)
    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").replace(Regex("""[^a-z0-9 ]+"""), " ").replace(Regex("""\s+"""), " ").trim()
}
