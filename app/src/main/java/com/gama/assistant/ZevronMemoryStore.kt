package com.gama.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Explicit local memory.
 *
 * Zevron only stores a durable fact when the owner explicitly says to remember
 * it. This keeps long-term memory useful without silently collecting an entire
 * conversation. Credentials and obvious secrets are intentionally rejected.
 */
object ZevronMemoryStore {
    private const val PREFS = "zevron_explicit_memory_v1"
    private const val KEY_FACTS = "facts"
    private const val MAX_FACTS = 24

    data class Fact(
        val text: String,
        val normalized: String,
        val createdAt: Long,
    )

    fun handle(context: Context, raw: String): String? {
        val clean = ZevronConversationEngine.stripWakeWord(raw).trim()
        val n = normalize(clean)
        if (n.isBlank()) return null

        val remember = Regex(
            "(?i)^(?:lembre|lembra|memorize|guarde)(?:\\s+disso|\\s+ai)?(?:\\s+que)?\\s+(.+)$"
        ).find(clean)
        if (remember != null) {
            val fact = remember.groupValues[1].trim().trim('.', '!', '?')
            if (fact.isBlank()) return "O que você quer que eu lembre?"
            if (looksSensitive(fact)) {
                return "Prefiro não guardar senhas, PINs, tokens ou dados de pagamento na memória."
            }
            add(context, fact)
            return "Certo. Vou lembrar disso."
        }

        if (n in setOf(
                "o que voce lembra", "o que voce lembra de mim", "o que esta na sua memoria",
                "quais coisas voce lembra", "mostre sua memoria", "mostra sua memoria"
            )
        ) {
            val facts = load(context)
            if (facts.isEmpty()) return "Você ainda não me pediu para guardar nenhuma informação permanente."
            return buildString {
                append("Eu lembro: ")
                facts.takeLast(6).forEachIndexed { index, fact ->
                    if (index > 0) append("; ")
                    append(fact.text)
                }
                append('.')
            }
        }

        val forget = Regex(
            "(?i)^(?:esqueca|esqueça|apague|remova|tire)(?:\\s+da\\s+memoria|\\s+da\\s+memória)?(?:\\s+que)?\\s+(.+)$"
        ).find(clean)
        if (forget != null) {
            val query = forget.groupValues[1].trim()
            val removed = removeMatching(context, query)
            return if (removed > 0) "Certo. Removi isso da memória." else "Não encontrei essa informação na minha memória."
        }

        if (n in setOf("limpe sua memoria", "limpa sua memoria", "apague sua memoria", "apaga sua memoria")) {
            clear(context)
            return "Certo. Apaguei as informações que você tinha me pedido para guardar."
        }

        return null
    }

    fun brainContext(context: Context): String? {
        val facts = load(context).takeLast(8)
        if (facts.isEmpty()) return null
        return "Memórias explícitas fornecidas pelo usuário: " + facts.joinToString(" | ") { it.text.take(180) }
    }

    fun add(context: Context, text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ").take(360)
        if (clean.isBlank()) return
        val n = normalize(clean)
        val facts = load(context).filterNot { it.normalized == n }.toMutableList()
        facts += Fact(clean, n, System.currentTimeMillis())
        save(context, facts.takeLast(MAX_FACTS))
    }

    fun load(context: Context): List<Fact> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FACTS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val text = obj.optString("text").trim()
                    if (text.isBlank()) continue
                    add(
                        Fact(
                            text = text,
                            normalized = obj.optString("normalized").ifBlank { normalize(text) },
                            createdAt = obj.optLong("createdAt", 0L),
                        )
                    )
                }
            }.takeLast(MAX_FACTS)
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_FACTS).apply()
    }

    private fun removeMatching(context: Context, query: String): Int {
        val q = normalize(query)
        if (q.isBlank()) return 0
        val before = load(context)
        val after = before.filterNot { it.normalized.contains(q) || q.contains(it.normalized) }
        val removed = before.size - after.size
        if (removed > 0) save(context, after)
        return removed
    }

    private fun save(context: Context, facts: List<Fact>) {
        val array = JSONArray()
        facts.takeLast(MAX_FACTS).forEach { fact ->
            array.put(
                JSONObject()
                    .put("text", fact.text)
                    .put("normalized", fact.normalized)
                    .put("createdAt", fact.createdAt)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FACTS, array.toString())
            .apply()
    }

    private fun looksSensitive(raw: String): Boolean {
        val n = normalize(raw)
        return listOf(
            "senha", "password", "pin", "codigo de seguranca", "cvv", "token",
            "chave privada", "private key", "numero do cartao", "cartao de credito"
        ).any { it in n }
    }

    internal fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
