package com.gama.assistant

import android.content.Context
import org.json.JSONObject

object GamaPendingProposalStore {
    private const val PREFS = "gama_pending_proposal"
    private const val KEY_JSON = "pending_json"
    private const val TTL_MS = 10L * 60L * 1000L

    data class Proposal(
        val tool: String,
        val payload: String,
        val prompt: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    fun save(context: Context, proposal: Proposal) {
        val json = JSONObject()
            .put("tool", proposal.tool)
            .put("payload", proposal.payload)
            .put("prompt", proposal.prompt)
            .put("createdAt", proposal.createdAt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, json.toString())
            .apply()
    }

    fun load(context: Context, now: Long = System.currentTimeMillis()): Proposal? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
            ?: return null
        val proposal = runCatching {
            val json = JSONObject(raw)
            Proposal(
                tool = json.optString("tool"),
                payload = json.optString("payload"),
                prompt = json.optString("prompt"),
                createdAt = json.optLong("createdAt", 0L),
            )
        }.getOrNull() ?: run {
            clear(context)
            return null
        }

        if (
            proposal.tool.isBlank() ||
            proposal.prompt.isBlank() ||
            proposal.createdAt <= 0L ||
            now - proposal.createdAt > TTL_MS
        ) {
            clear(context)
            return null
        }
        return proposal
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_JSON)
            .apply()
    }
}
