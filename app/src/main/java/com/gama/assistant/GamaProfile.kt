package com.gama.assistant

import android.content.Context
import org.json.JSONArray

/** Somente comandos explícitos armazenam fatos pessoais no próprio aparelho. */
object GamaProfile {
    private const val STORE = "gama_profile_v1"
    private fun prefs(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    fun title(context: Context): String = OwnerIdentity.treatment(context)
    fun setTitle(context: Context, value: String) {
        // Alterações de tratamento são feitas em Ajustes após autenticação do Android.
    }
    fun focus(context: Context): Boolean = prefs(context).getBoolean("focus", false)
    fun facts(context: Context): List<String> = try {
        val arr = JSONArray(prefs(context).getString("facts", "[]") ?: "[]")
        (0 until minOf(arr.length(), 8)).map { arr.optString(it).take(160) }.filter(String::isNotBlank)
    } catch (_: Exception) { emptyList() }
    fun forget(context: Context) { prefs(context).edit().remove("facts").apply() }
    fun remember(context: Context, value: String): Boolean {
        val fact = value.trim().replace(Regex("\\s+"), " ").take(160)
        if (fact.length < 3) return false
        val remembered = (facts(context).filterNot { it.equals(fact, ignoreCase = true) } + fact).takeLast(8)
        val arr = JSONArray()
        remembered.forEach { arr.put(it) }
        prefs(context).edit().putString("facts", arr.toString()).apply()
        return true
    }
    fun handle(context: Context, raw: String): String? {
        val normalized = VoicePolicy.normalize(raw)
        if (normalized.startsWith("me chame de ")) {
            return "O nome e o tratamento são configurados em Ajustes, depois da confirmação do Android."
        }
        if (normalized in setOf("ativar modo foco", "ative modo foco", "ativar protocolo de foco", "protocolo de foco")) {
            prefs(context).edit().putBoolean("focus", true).apply()
            return FocusControl.enable(context)
        }
        if (normalized in setOf("desativar modo foco", "desative modo foco", "desativar protocolo de foco")) {
            prefs(context).edit().putBoolean("focus", false).apply()
            return FocusControl.disable(context)
        }
        if (normalized in setOf("apague minha memoria", "esqueca o que lembra de mim", "apagar memoria pessoal")) {
            forget(context)
            return "Memória pessoal do Gama apagada. As notas e a identidade configurada são separadas e não foram alteradas."
        }
        if (normalized in setOf("o que voce lembra de mim", "minha memoria", "o que voce sabe sobre mim")) {
            val saved = facts(context)
            return if (saved.isEmpty()) "Não tenho fatos pessoais salvos. Diga 'lembre que' para guardar uma preferência no aparelho."
                else "Tenho ${saved.size} informação(ões) guardada(s): " + saved.takeLast(3).joinToString("; ")
        }
        if (normalized in setOf("ativar avisos inteligentes", "ative avisos inteligentes", "ativar avisos proativos")) {
            return GamaPulse.toggle(context, true)
        }
        if (normalized in setOf("desativar avisos inteligentes", "desative avisos inteligentes", "desativar avisos proativos")) {
            return GamaPulse.toggle(context, false)
        }
        if (normalized.startsWith("lembre que ")) {
            val fact = raw.trim().replaceFirst(Regex("(?i)^lembre\\s+que\\s+"), "").trim()
            return if (remember(context, fact)) "Memorizado neste aparelho. Pode apagar isso em Ajustes ou por comando."
                else "Não encontrei uma informação para memorizar."
        }
        return null
    }
}
