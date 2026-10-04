package com.gama.assistant

import android.content.Context
import android.os.BatteryManager
import android.app.KeyguardManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a compact, local-only situation snapshot for the conversational brain. */
object GamaContextFusion {
    fun snapshot(context: Context): String? {
        val pieces = mutableListOf<String>()
        val now = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())
        pieces += "Hora local do aparelho: $now."
        val battery = runCatching {
            context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        if (battery != null && battery in 0..100) pieces += "Bateria: $battery%."
        GamaScreenContextService.snapshotNow()?.let { snap ->
            val title = snap.title.takeIf { it.isNotBlank() }
            val app = snap.packageName.takeIf { it.isNotBlank() }
            if (title != null || app != null) pieces += "Tela atual: ${title ?: app}."
            val locked = runCatching {
                context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
            }.getOrDefault(true)
            if (!locked) {
                val visible = snap.plainText(700)
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (visible.isNotBlank()) pieces += "Texto visível agora: ${visible.take(700)}."
            }
        }
        GamaEventHub.contextSummary(context)?.let(pieces::add)
        GamaMissionStore.contextSummary(context)?.let(pieces::add)
        return pieces.joinToString(" ").take(3600).takeIf { it.isNotBlank() }
    }
}
