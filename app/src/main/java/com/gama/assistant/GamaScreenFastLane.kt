package com.gama.assistant

import android.content.Context

/** Local-first screen commands. Never sends screen text to the model just to read it aloud. */
object GamaScreenFastLane {
    /** Pure routing check used by tests and by the local screen fast lane. */
    fun isExplicitScreenCommand(raw: String): Boolean {
        val c = GamaTimePolicy.normalize(raw)

        // FIX93_STRICT_SCREEN_TRIGGER: never treat vague speech such as "veja isso"
        // as a screen command. In a noisy room that phrase can be produced by a bad
        // transcription and would otherwise hijack unrelated commands.
        val explicitScreenReference =
            Regex("(?:^|\\s)(?:tela|display|visor)(?:$|\\s)").containsMatchIn(c) ||
                c == "o que estou vendo" ||
                c == "o que eu estou vendo"
        if (!explicitScreenReference) return false

        if (c.startsWith("nao ") || " nao leia " in " $c " || " nao veja " in " $c ") return false

        val wantsSummary =
            ("resuma" in c || "resumo" in c) &&
                ("tela" in c || "display" in c || "visor" in c)

        val wantsRead = !wantsSummary && (
            "leia" in c || "ler" in c || "le " in " $c " ||
                "o que tem" in c || "o que esta" in c || "o que aparece" in c ||
                "veja" in c || "olhe" in c ||
                c == "o que estou vendo" || c == "o que eu estou vendo"
        )
        return wantsRead || wantsSummary
    }

    fun handle(context: Context, raw: String): String? {
        if (!isExplicitScreenCommand(raw)) return null
        val c = GamaTimePolicy.normalize(raw)
        val wantsSummary = ("resuma" in c || "resumo" in c)

        if (!GamaScreenContextService.isConnected()) {
            GamaScreenContextService.openSettings(context)
            return "Para eu ler a tela, ative Gama Contexto da Tela na acessibilidade. Abri essa configuração para você."
        }

        val snapshot = GamaScreenContextService.snapshotNow()
            ?: return "Estou com acesso à tela, mas o Android não me entregou texto legível agora. Deixe a tela que você quer ler visível e peça de novo."

        if (wantsSummary) return snapshot.localSummary()
        val full = snapshot.plainText(5000)
        if (full.isBlank()) return "Não encontrei texto legível nessa tela."
        return "Na tela está escrito: $full"
    }
}
