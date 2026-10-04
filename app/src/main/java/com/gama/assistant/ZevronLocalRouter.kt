package com.gama.assistant

import android.content.Context

/** Deterministic local tools only. Session lifetime belongs to GamaService. */
object ZevronLocalRouter {
    fun handle(context: Context, raw: String): String? {
        ZevronMemoryStore.handle(context, raw)?.let { return it }

        ZevronSystemControl.handle(context, raw)?.let { return verified(context, "interface", it) }
        ZevronCalendarFlow.handle(context, raw)?.let { return verified(context, "calendário", it) }
        ZevronAppTool.handle(context, raw)?.let { return verified(context, "aplicativo", it) }
        ZevronMathTool.handle(context, raw)?.let { return verified(context, "matemática", it) }
        return null
    }

    private fun verified(context: Context, source: String, reply: String): String {
        GamaTaskMemory.rememberResult(context, reply)
        GamaEventHub.record(
            context,
            GamaEventHub.Event(
                kind = GamaEventHub.Kind.ACTION,
                source = source,
                summary = reply,
                priority = GamaEventHub.Priority.LOW,
            )
        )
        return reply
    }
}
