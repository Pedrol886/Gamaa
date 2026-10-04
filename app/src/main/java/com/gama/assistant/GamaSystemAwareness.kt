package com.gama.assistant

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

object GamaSystemAwareness {
    private const val PREFS = "gama_system_awareness"
    private const val KEY_LOW_BATTERY_AT = "low_battery_at"
    private const val KEY_STORAGE_AT = "storage_at"
    private const val KEY_THERMAL_AT = "thermal_at"
    private const val LOW_BATTERY_COOLDOWN_MS = 90L * 60L * 1000L
    private const val STORAGE_COOLDOWN_MS = 6L * 60L * 60L * 1000L
    private const val THERMAL_COOLDOWN_MS = 30L * 60L * 1000L

    fun poll(context: Context, now: Long = System.currentTimeMillis()): GamaEventHub.Event? {
        thermalEvent(context, now)?.let { return it }
        batteryEvent(context, now)?.let { return it }
        storageEvent(context, now)?.let { return it }
        return null
    }

    private fun thermalEvent(context: Context, now: Long): GamaEventHub.Event? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pm = context.getSystemService(PowerManager::class.java) ?: return null
        val status = runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        if (status < PowerManager.THERMAL_STATUS_SEVERE) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_THERMAL_AT, 0L)
        if (last > 0L && now - last < THERMAL_COOLDOWN_MS) return null
        prefs.edit().putLong(KEY_THERMAL_AT, now).apply()
        return GamaEventHub.Event(
            kind = GamaEventHub.Kind.SYSTEM,
            source = "Temperatura",
            summary = "O aparelho está muito quente. Vale reduzir a carga e deixar o celular esfriar.",
            priority = GamaEventHub.Priority.HIGH,
            occurredAt = now,
        )
    }

    private fun batteryEvent(context: Context, now: Long): GamaEventHub.Event? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..15 || bm.isCharging) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LOW_BATTERY_AT, 0L)
        if (last > 0L && now - last < LOW_BATTERY_COOLDOWN_MS) return null
        prefs.edit().putLong(KEY_LOW_BATTERY_AT, now).apply()
        return GamaEventHub.Event(
            kind = GamaEventHub.Kind.SYSTEM,
            source = "Bateria",
            summary = if (level <= 5) {
                "A bateria está em $level%. Conecte o carregador assim que puder."
            } else {
                "A bateria está em $level%."
            },
            priority = GamaEventHub.Priority.HIGH,
            occurredAt = now,
        )
    }

    private fun storageEvent(context: Context, now: Long): GamaEventHub.Event? {
        val fs = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull() ?: return null
        val available = fs.availableBytes
        val total = fs.totalBytes.coerceAtLeast(1L)
        val low = available < 1_500_000_000L || available.toDouble() / total.toDouble() < 0.05
        if (!low) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_STORAGE_AT, 0L)
        if (last > 0L && now - last < STORAGE_COOLDOWN_MS) return null
        prefs.edit().putLong(KEY_STORAGE_AT, now).apply()
        val mb = available / (1024L * 1024L)
        return GamaEventHub.Event(
            kind = GamaEventHub.Kind.SYSTEM,
            source = "Armazenamento",
            summary = "O armazenamento está quase cheio. Restam aproximadamente $mb MB livres.",
            priority = GamaEventHub.Priority.HIGH,
            occurredAt = now,
        )
    }
}
