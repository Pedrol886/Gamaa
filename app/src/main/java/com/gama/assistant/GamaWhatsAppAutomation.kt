package com.gama.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.Locale

object GamaWhatsAppAutomation {
    private enum class Stage { PICK, SEARCH, AFTER_PICK, VERIFY }

    private data class Pending(
        val recipient: String,
        val message: String,
        var stage: Stage,
        val startedAt: Long,
        val packageName: String,
        var sendClickedAt: Long = 0L,
    )

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var pending: Pending? = null
    @Volatile private var resultCallback: ((String) -> Unit)? = null
    @Volatile private var searchTyped = false

    fun start(context: Context, request: GamaWhatsAppSendParser.Request, callback: (String) -> Unit) {
        if (!GamaScreenContextService.isConnected()) {
            GamaScreenContextService.openSettings(context)
            callback("Para enviar pelo WhatsApp eu preciso do Gama Contexto da Tela ativado na acessibilidade. Abri essa configuração.")
            return
        }
        val packageName = when {
            context.packageManager.getLaunchIntentForPackage("com.whatsapp") != null -> "com.whatsapp"
            context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b") != null -> "com.whatsapp.w4b"
            else -> null
        }
        if (packageName == null) {
            callback("Não encontrei o WhatsApp instalado.")
            return
        }

        pending = Pending(request.recipient, request.message, Stage.PICK, System.currentTimeMillis(), packageName)
        resultCallback = callback
        searchTyped = false

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, request.message)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(intent) }.isFailure) {
            finish("Não consegui abrir o WhatsApp para concluir o envio.")
            return
        }

        handler.postDelayed({
            val active = pending ?: return@postDelayed
            if (System.currentTimeMillis() - active.startedAt >= 24_000L) {
                finish("O WhatsApp não confirmou o envio a tempo. A mensagem não foi marcada como enviada para evitar uma confirmação falsa.")
            }
        }, 24_500L)
    }

    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        val current = pending ?: return
        if (System.currentTimeMillis() - current.startedAt > 24_000L) return
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank() && pkg != current.packageName) return
        pump(service)
    }

    private fun pump(service: AccessibilityService) {
        val current = pending ?: return
        val root = service.rootInActiveWindow ?: return
        when (current.stage) {
            Stage.PICK, Stage.SEARCH -> pickRecipient(service, root, current)
            Stage.AFTER_PICK -> advanceAndSend(service, root, current)
            Stage.VERIFY -> verifySent(service, root, current)
        }
    }

    private fun pickRecipient(service: AccessibilityService, root: AccessibilityNodeInfo, current: Pending) {
        val candidates = recipientCandidates(root, current.recipient)
        if (candidates.size == 1) {
            if (candidates.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                current.stage = Stage.AFTER_PICK
                pulse(service, 350L)
                pulse(service, 900L)
            }
            return
        }
        if (candidates.size > 1) {
            finish("Encontrei mais de uma conversa com esse nome. Diga um nome mais específico para eu não enviar para a pessoa errada.")
            return
        }

        if (!searchTyped && current.stage == Stage.SEARCH) {
            typeSearch(service, current.recipient)
            if (searchTyped) return
        }

        if (!searchTyped) {
            val searchButton = findFirst(root) { node ->
                val label = nodeLabel(node)
                val id = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
                node.isClickable && (label in setOf("pesquisar", "search", "buscar") || "search" in id)
            }
            if (searchButton != null && clickNodeOrParent(searchButton)) {
                current.stage = Stage.SEARCH
                pulse(service, 300L)
                pulse(service, 700L)
                return
            }
        } else if (current.stage == Stage.SEARCH) {
            pulse(service, 250L)
        }
    }

    private fun typeSearch(service: AccessibilityService, recipient: String) {
        val root = service.rootInActiveWindow ?: return
        val editable = findFirst(root) { node ->
            node.isEditable && isSearchNode(node)
        } ?: findFirst(root) { it.isEditable }
        if (editable != null && setText(editable, recipient)) {
            searchTyped = true
            pulse(service, 450L)
        }
    }

    private fun advanceAndSend(service: AccessibilityService, root: AccessibilityNodeInfo, current: Pending) {
        // Some WhatsApp versions show an intermediate "next/continue" after picking a contact.
        val next = findFirst(root) { node ->
            val label = nodeLabel(node)
            node.isClickable && label in setOf("avancar", "avançar", "next", "continuar", "continue")
        }
        if (next != null && clickNodeOrParent(next)) {
            pulse(service, 450L)
            return
        }

        val editor = findMessageEditor(root)
        if (editor != null) {
            val currentText = editor.text?.toString().orEmpty()
            if (normalize(currentText) != normalize(current.message)) {
                if (!setText(editor, current.message)) return
                pulse(service, 250L)
            }
        }

        val send = findSend(root) ?: return
        if (clickNodeOrParent(send)) {
            current.sendClickedAt = System.currentTimeMillis()
            current.stage = Stage.VERIFY
            pulse(service, 500L)
            pulse(service, 1_200L)
            pulse(service, 2_400L)
        }
    }

    private fun verifySent(service: AccessibilityService, root: AccessibilityNodeInfo, current: Pending) {
        val messageVisible = textMatches(root, current.message).isNotEmpty()
        val editor = findMessageEditor(root)
        val editorStillContainsMessage = editor?.text?.toString()?.let { normalize(it) == normalize(current.message) } == true
        if (messageVisible && !editorStillContainsMessage) {
            finish("Mensagem enviada para ${current.recipient} no WhatsApp.")
            return
        }
        if (current.sendClickedAt > 0L && System.currentTimeMillis() - current.sendClickedAt > 3_500L) {
            finish("Toquei em enviar no WhatsApp, mas não consegui confirmar na tela que a mensagem saiu. Não vou afirmar que foi enviada.")
        }
    }

    private fun recipientCandidates(root: AccessibilityNodeInfo, recipient: String): List<AccessibilityNodeInfo> {
        val wanted = normalize(recipient)
        if (wanted.isBlank()) return emptyList()
        val exact = mutableListOf<AccessibilityNodeInfo>()
        val partial = mutableListOf<AccessibilityNodeInfo>()
        walk(root, 0) { node ->
            val label = nodeLabel(node)
            if (label == wanted) clickableAncestor(node)?.let(exact::add)
            else if (wanted.length >= 4 && label.contains(wanted)) clickableAncestor(node)?.let(partial::add)
        }
        return dedupe(if (exact.isNotEmpty()) exact else partial)
    }

    private fun dedupe(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val seen = mutableSetOf<String>()
        return nodes.filter { node ->
            val rect = Rect().also(node::getBoundsInScreen)
            seen.add("${rect.left},${rect.top},${rect.right},${rect.bottom}|${node.viewIdResourceName.orEmpty()}")
        }
    }

    private fun findMessageEditor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) { node ->
            if (!node.isEditable || isSearchNode(node)) return@findFirst false
            val id = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
            val label = nodeLabel(node)
            listOf("entry", "message", "input", "compose", "conversation").any { it in id } ||
                listOf("mensagem", "message", "digite", "type a message").any { it in label }
        } ?: findFirst(root) { it.isEditable && !isSearchNode(it) }

    private fun isSearchNode(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
        val label = nodeLabel(node)
        return "search" in id || "pesquis" in label || label == "buscar" || label == "search"
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) { node ->
            val id = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
            val label = nodeLabel(node)
            node.isClickable && (
                id.endsWith(":id/send") || "send" in id ||
                    label == "enviar" || label == "send" ||
                    label == "enviar mensagem" || label == "send message" ||
                    label.contains("enviar") || label.contains("send")
                )
        }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun textMatches(root: AccessibilityNodeInfo, target: String): List<AccessibilityNodeInfo> {
        val wanted = normalize(target)
        val out = mutableListOf<AccessibilityNodeInfo>()
        if (wanted.isBlank()) return out
        walk(root, 0) { node ->
            val label = nodeLabel(node)
            if (label == wanted || (wanted.length >= 5 && label.contains(wanted))) out += node
        }
        return out
    }

    private fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root, 0) { node -> if (found == null && predicate(node)) found = node }
        return found
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, block: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 22) return
        block(node)
        val count = node.childCount.coerceAtMost(60)
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, block)
            if (pending == null) return
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val n = current ?: return null
            if (n.isClickable) return n
            current = n.parent
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        val target = clickableAncestor(node) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String = normalize(
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
    )

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun pulse(service: AccessibilityService, delayMs: Long) {
        handler.postDelayed({ if (pending != null) pump(service) }, delayMs)
    }

    private fun finish(result: String) {
        val cb = resultCallback
        pending = null
        resultCallback = null
        searchTyped = false
        handler.post { cb?.invoke(result) }
    }
}
