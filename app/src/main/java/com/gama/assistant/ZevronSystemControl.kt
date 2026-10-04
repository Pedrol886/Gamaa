package com.gama.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.text.Normalizer
import java.util.Locale

/**
 * High-level, user-authorized Android control surface.
 *
 * This deliberately uses only public Android accessibility actions and only
 * becomes active after the user enables Zevron's accessibility service.
 * It never bypasses the lock screen, PIN, password or biometrics.
 */
object ZevronSystemControl {

    private val backCommands = setOf(
        "volte", "voltar", "volte uma tela", "volta uma tela", "va para tras", "ir para tras"
    )
    private val homeCommands = setOf(
        "inicio", "ir para o inicio", "va para o inicio", "tela inicial", "va para a tela inicial"
    )
    private val recentsCommands = setOf(
        "recentes", "abra os recentes", "mostre os recentes", "aplicativos recentes", "apps recentes"
    )
    private val notificationCommands = setOf(
        "notificacoes", "abra as notificacoes", "mostre as notificacoes", "painel de notificacoes"
    )
    private val quickSettingsCommands = setOf(
        "configuracoes rapidas", "abra as configuracoes rapidas", "painel rapido", "atalhos rapidos"
    )
    private val lockCommands = setOf(
        "bloqueie a tela", "bloqueia a tela", "trave a tela", "trava a tela", "bloqueie o celular", "trave o celular"
    )

    fun recognizes(raw: String): Boolean {
        val c = command(raw)
        if (c in backCommands || c in homeCommands || c in recentsCommands ||
            c in notificationCommands || c in quickSettingsCommands || c in lockCommands) return true
        if (clickTarget(c) != null || textToType(c) != null || scrollDirection(c) != null) return true
        return false
    }

    fun handle(context: Context, raw: String): String? {
        val c = command(raw)
        if (!recognizes(c)) return null

        // Locking is handled by GamaService because it can defer the actual lock
        // until speech finishes and can use Accessibility OR Device Admin.
        if (c in lockCommands) return null

        if (!GamaScreenContextService.isConnected()) {
            GamaScreenContextService.openSettings(context)
            return "Para controlar a interface inteira por voz, ative Gama Contexto da Tela na acessibilidade. Abri a configuração."
        }

        when {
            c in backCommands -> return result(
                GamaScreenContextService.performGlobal(AccessibilityService.GLOBAL_ACTION_BACK),
                "Voltando.", "Não consegui voltar nesta tela."
            )
            c in homeCommands -> return result(
                GamaScreenContextService.performGlobal(AccessibilityService.GLOBAL_ACTION_HOME),
                "Indo para a tela inicial.", "Não consegui abrir a tela inicial."
            )
            c in recentsCommands -> return result(
                GamaScreenContextService.performGlobal(AccessibilityService.GLOBAL_ACTION_RECENTS),
                "Abrindo os aplicativos recentes.", "Não consegui abrir os aplicativos recentes."
            )
            c in notificationCommands -> return result(
                GamaScreenContextService.performGlobal(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
                "Abrindo as notificações.", "Não consegui abrir as notificações."
            )
            c in quickSettingsCommands -> return result(
                GamaScreenContextService.performGlobal(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
                "Abrindo as configurações rápidas.", "Não consegui abrir as configurações rápidas."
            )
            c in lockCommands -> return result(
                GamaScreenContextService.performGlobal(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),
                "Bloqueando a tela.", "Não consegui bloquear a tela."
            )
        }

        clickTarget(c)?.let { target ->
            return result(
                GamaScreenContextService.clickByLabel(target),
                "Toquei em $target.",
                "Não encontrei $target nesta tela."
            )
        }

        textToType(c)?.let { text ->
            return result(
                GamaScreenContextService.replaceFocusedText(text),
                "Texto preenchido.",
                "Não encontrei um campo de texto ativo nesta tela."
            )
        }

        scrollDirection(c)?.let { down ->
            val before = screenFingerprint()
            val sent = GamaScreenContextService.scroll(down)
            if (!sent) return "Não consegui rolar esta tela."

            // Accessibility accepting a gesture is not proof that the content
            // actually moved. When we are off the UI thread, wait briefly and
            // compare a fresh snapshot before claiming success.
            if (Looper.myLooper() != Looper.getMainLooper()) {
                SystemClock.sleep(420L)
            }
            val after = screenFingerprint()
            return if (before != null && after != null && before != after) {
                if (down) "Rolei a tela para baixo e confirmei a mudança."
                else "Rolei a tela para cima e confirmei a mudança."
            } else {
                "Enviei o gesto de rolagem, mas não consegui confirmar que a tela mudou."
            }
        }

        return null
    }

    internal fun clickTarget(raw: String): String? {
        val c = command(raw)
        val prefixes = listOf(
            "toque em ", "toque no ", "toque na ", "aperte ", "aperte em ",
            "clique em ", "clique no ", "clique na ", "selecione ", "selecione o ", "selecione a "
        )
        val prefix = prefixes.firstOrNull { c.startsWith(it) } ?: return null
        return c.removePrefix(prefix).trim().takeIf { it.length in 1..120 }
    }

    internal fun textToType(raw: String): String? {
        val c = command(raw)
        val prefixes = listOf("digite ", "escreva ", "preencha com ", "coloque o texto ")
        val prefix = prefixes.firstOrNull { c.startsWith(it) } ?: return null
        return c.removePrefix(prefix).trim().takeIf { it.length in 1..1000 }
    }

    internal fun scrollDirection(raw: String): Boolean? {
        val c = command(raw)
        if (c in setOf(
                "desca", "desce", "role para baixo", "rola para baixo", "role a tela para baixo",
                "rola a tela para baixo", "mais para baixo", "desca a tela", "desce a tela",
                "role a tela", "rola a tela", "role mais", "rola mais", "continue rolando",
                "continue descendo", "vai descendo", "proxima parte", "proxima tela"
            )) return true
        if (c in setOf(
                "suba", "sobe", "role para cima", "rola para cima", "role a tela para cima",
                "rola a tela para cima", "mais para cima", "suba a tela", "sobe a tela",
                "volte rolando", "parte anterior"
            )) return false
        if (("rol" in c || "desc" in c) && "baixo" in c) return true
        if (("rol" in c || "sub" in c) && "cima" in c) return false
        return null
    }

    private fun result(ok: Boolean, success: String, failure: String): String = if (ok) success else failure

    private fun screenFingerprint(): String? {
        val snapshot = GamaScreenContextService.snapshotNow() ?: return null
        return buildString {
            append(snapshot.packageName).append('|').append(snapshot.title).append('|')
            snapshot.lines.take(18).forEach { append(it).append('\u001f') }
        }.take(2400)
    }

    private fun command(raw: String): String = normalize(
        ZevronConversationEngine.stripWakeWord(raw).trim()
    )

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
