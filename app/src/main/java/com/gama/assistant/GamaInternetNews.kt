package com.gama.assistant

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

object GamaInternetNews {
    private const val PREFS = "gama_internet_news"
    private const val KEY_HEADLINES = "headlines"
    private const val KEY_UPDATED = "updated"
    private const val MAX_CACHE_AGE_MS = 6L * 60L * 60L * 1000L
    private const val FEED =
        "https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419"

    data class Result(
        val headlines: List<String>,
        val fresh: Boolean,
    )

    fun fetch(context: Context, now: Long = System.currentTimeMillis()): Result {
        val online = runCatching { downloadHeadlines() }.getOrDefault(emptyList())
        if (online.isNotEmpty()) {
            save(context, online, now)
            return Result(online, true)
        }
        val cached = load(context)
        val updated = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UPDATED, 0L)
        return Result(
            headlines = if (updated > 0L && now - updated <= MAX_CACHE_AGE_MS) cached else emptyList(),
            fresh = false,
        )
    }

    private fun downloadHeadlines(): List<String> {
        val connection = (URL(FEED).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_500
            readTimeout = 4_500
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Gama/6.0 Android morning briefing")
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            connection.inputStream.use { stream ->
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = false
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                }
                val doc = factory.newDocumentBuilder().parse(stream)
                val items = doc.getElementsByTagName("item")
                buildList {
                    for (i in 0 until items.length) {
                        val item = items.item(i)
                        val children = item.childNodes
                        var title = ""
                        for (j in 0 until children.length) {
                            val child = children.item(j)
                            if (child.nodeName.equals("title", ignoreCase = true)) {
                                title = child.textContent.orEmpty()
                                break
                            }
                        }
                        val clean = cleanHeadline(title)
                        if (clean.isNotBlank() && clean !in this) add(clean)
                        if (size >= 5) break
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cleanHeadline(value: String): String =
        value.replace(Regex("\\s+"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
            .take(220)

    private fun save(context: Context, headlines: List<String>, now: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HEADLINES, headlines.joinToString("\u001F"))
            .putLong(KEY_UPDATED, now)
            .apply()
    }

    private fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HEADLINES, null)
            .orEmpty()
            .split('\u001F')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(5)
}
