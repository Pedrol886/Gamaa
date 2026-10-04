package com.gama.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

/**
 * User-enabled accessibility bridge used only when the user explicitly asks
 * Gama to read or research what is visible. Password nodes are skipped and
 * screen text is never persisted by this service.
 */
class GamaScreenContextService : AccessibilityService() {
    companion object {
        @Volatile
        private var active: WeakReference<GamaScreenContextService>? = null

        // GAMA92_RECENT_SCREEN_CACHE
        @Volatile private var lastSnapshot: GamaScreenSnapshot? = null

        fun isConnected(): Boolean = active?.get() != null

        fun currentPackageName(): String? =
            snapshotNow()?.packageName?.takeIf { it.isNotBlank() }

        fun snapshotNow(): GamaScreenSnapshot? {
            val live = active?.get()?.captureSnapshot()
            if (live != null) {
                lastSnapshot = live
                return live
            }
            return lastSnapshot?.takeIf { System.currentTimeMillis() - it.capturedAt <= 8_000L }
        }

        fun openSettings(context: Context): Boolean = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)

        fun performGlobal(action: Int): Boolean =
            runCatching { active?.get()?.performGlobalAction(action) == true }.getOrDefault(false)

        fun clickByLabel(label: String): Boolean =
            runCatching { active?.get()?.clickByLabelInternal(label) == true }.getOrDefault(false)

        fun replaceFocusedText(value: String): Boolean =
            runCatching { active?.get()?.replaceFocusedTextInternal(value) == true }.getOrDefault(false)

        fun scroll(down: Boolean): Boolean =
            runCatching { active?.get()?.scrollInternal(down) == true }.getOrDefault(false)

        fun captureCurrentBitmap(): Bitmap? =
            runCatching { active?.get()?.captureCurrentBitmapInternal() }.getOrNull()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = WeakReference(this)
        AssistantRuntime.service?.resumePendingAccessibilityActions()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        captureSnapshot()?.let { snapshot ->
            lastSnapshot = snapshot
            GamaScreenMemory.observe(applicationContext, snapshot)
        }
        GamaWhatsAppAutomation.onAccessibilityEvent(this, event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    private fun captureCurrentBitmapInternal(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val result = AtomicReference<Bitmap?>(null)
        val latch = CountDownLatch(1)

        return try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(
                        screenshot: AccessibilityService.ScreenshotResult
                    ) {
                        try {
                            val buffer = screenshot.hardwareBuffer
                            try {
                                result.set(
                                    Bitmap.wrapHardwareBuffer(
                                        buffer,
                                        screenshot.colorSpace
                                    )?.copy(Bitmap.Config.ARGB_8888, false)
                                )
                            } finally {
                                runCatching { buffer.close() }
                            }
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )

            latch.await(2200L, TimeUnit.MILLISECONDS)
            result.get()
        } catch (_: Throwable) {
            null
        }
    }

    private fun captureSnapshot(): GamaScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        val lines = mutableListOf<String>()
        collect(root, lines, depth = 0)
        val distinct = lines
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(80)
        if (distinct.isEmpty()) return null
        val title = distinct.firstOrNull().orEmpty().take(160)
        return GamaScreenSnapshot(
            packageName = packageName,
            title = title,
            lines = distinct,
        )
    }


    private fun clickByLabelInternal(label: String): Boolean {
        val wanted = normalizeUi(label)
        if (wanted.isBlank()) return false
        val root = rootInActiveWindow ?: return false

        var partial: AccessibilityNodeInfo? = null
        var exact: AccessibilityNodeInfo? = null
        walkNodes(root, 0) { node ->
            if (exact != null) return@walkNodes
            val text = normalizeUi(
                listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .joinToString(" ")
            )
            if (text.isBlank()) return@walkNodes
            val clickable = clickableAncestor(node) ?: return@walkNodes
            if (text == wanted) exact = clickable
            else if (partial == null && (text.contains(wanted) || wanted.contains(text))) partial = clickable
        }
        return (exact ?: partial)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun replaceFocusedTextInternal(value: String): Boolean {
        if (value.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target?.isEditable != true) {
            target = null
            walkNodes(root, 0) { node ->
                if (target == null && node.isEditable && node.isVisibleToUser) target = node
            }
        }
        val editable = target ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value.take(1000))
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun scrollInternal(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        walkNodes(root, 0) { node ->
            if (node.isVisibleToUser && node.isScrollable) candidates += node
        }

        val legacyAction = if (down) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val directionalAction = if (down) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        } else {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        }

        for (target in candidates.asReversed()) {
            if (runCatching { target.performAction(directionalAction) }.getOrDefault(false)) return true
            if (runCatching { target.performAction(legacyAction) }.getOrDefault(false)) return true
        }
        if (runCatching { root.performAction(directionalAction) }.getOrDefault(false)) return true
        if (runCatching { root.performAction(legacyAction) }.getOrDefault(false)) return true

        // WebViews, custom feeds and canvas-heavy apps often expose no scrollable node.
        // A user-enabled AccessibilityService may still perform the same swipe the
        // user requested, which gives us a reliable last-resort path.
        return swipeScroll(down)
    }

    private fun swipeScroll(down: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * if (down) 0.72f else 0.28f
        val endY = metrics.heightPixels * if (down) 0.28f else 0.72f
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 360L))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(7) {
            val item = current ?: return null
            if (item.isClickable && item.isEnabled && item.isVisibleToUser) return item
            current = item.parent
        }
        return null
    }

    private fun walkNodes(node: AccessibilityNodeInfo, depth: Int, block: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 24) return
        block(node)
        val count = node.childCount.coerceAtMost(80)
        for (index in 0 until count) {
            val child = node.getChild(index) ?: continue
            walkNodes(child, depth + 1, block)
        }
    }

    private fun normalizeUi(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (depth > 20 || out.size >= 100) return
        if (!node.isPassword) {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        }
        val count = node.childCount.coerceAtMost(40)
        for (index in 0 until count) {
            val child = node.getChild(index) ?: continue
            try {
                collect(child, out, depth + 1)
            } finally {
                child.recycle()
            }
            if (out.size >= 100) break
        }
    }
}
