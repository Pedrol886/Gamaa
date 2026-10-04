package com.gama.assistant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Small deterministic routine engine. It stores only user-approved rules and
 * local habit counters. It never records raw microphone audio or conversation text.
 */
class GamaAutomationEngine(private val context: Context) {
    companion object {
        private const val PREFS = "gama_automation_v1"
        private const val RULES = "rules"
        private const val PAUSED = "paused"
        private const val LAST_BATTERY = "last_battery"
        private const val HAS_BATTERY = "has_battery"
        private const val LAST_HEADSET = "last_headset"
        private const val HAS_HEADSET = "has_headset"
        private const val LAST_CHARGING = "last_charging"
        private const val HAS_CHARGING = "has_charging"
        private const val HABITS = "habits"
        private const val TAG = "GamaAutomation"
    }

    data class Rule(
        val id: Long,
        val triggerKind: AutomationTriggerKind,
        val triggerValue: String,
        val actionKind: AutomationActionKind,
        val action: String,
        val description: String,
        val enabled: Boolean = true,
        val lastFireToken: String = ""
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(draft: AutomationDraft): Rule {
        val rules = rules().toMutableList()
        val rule = Rule(
            id = System.currentTimeMillis(),
            triggerKind = draft.triggerKind,
            triggerValue = draft.triggerValue,
            actionKind = draft.actionKind,
            action = draft.action,
            description = draft.description
        )
        rules += rule
        saveRules(rules)
        return rule
    }

    @Synchronized
    fun rules(): List<Rule> {
        return try {
            val raw = prefs.getString(RULES, "[]").orEmpty()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val trigger = runCatching { AutomationTriggerKind.valueOf(o.optString("trigger")) }.getOrNull() ?: continue
                    val actionKind = runCatching { AutomationActionKind.valueOf(o.optString("actionKind")) }.getOrNull() ?: continue
                    add(
                        Rule(
                            id = o.optLong("id", i.toLong() + 1L),
                            triggerKind = trigger,
                            triggerValue = o.optString("triggerValue"),
                            actionKind = actionKind,
                            action = o.optString("action").take(240),
                            description = o.optString("description").take(300),
                            enabled = o.optBoolean("enabled", true),
                            lastFireToken = o.optString("lastFireToken")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler automações; preservando serviço principal", e)
            emptyList()
        }
    }

    @Synchronized
    private fun saveRules(rules: List<Rule>) {
        try {
            val arr = JSONArray()
            rules.take(60).forEach { rule ->
                arr.put(JSONObject().apply {
                    put("id", rule.id)
                    put("trigger", rule.triggerKind.name)
                    put("triggerValue", rule.triggerValue)
                    put("actionKind", rule.actionKind.name)
                    put("action", rule.action)
                    put("description", rule.description)
                    put("enabled", rule.enabled)
                    put("lastFireToken", rule.lastFireToken)
                })
            }
            prefs.edit().putString(RULES, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao salvar automações", e)
        }
    }

    @Synchronized
    fun pauseAll(): Int {
        prefs.edit().putBoolean(PAUSED, true).apply()
        return rules().count { it.enabled }
    }

    @Synchronized
    fun resumeAll(): Int {
        prefs.edit().putBoolean(PAUSED, false).apply()
        return rules().count { it.enabled }
    }

    fun paused(): Boolean = prefs.getBoolean(PAUSED, false)

    @Synchronized
    fun deleteAll(): Int {
        val count = rules().size
        prefs.edit().remove(RULES).apply()
        return count
    }

    @Synchronized
    fun deleteLast(): Rule? {
        val list = rules().toMutableList()
        val removed = list.maxByOrNull { it.id } ?: return null
        list.removeAll { it.id == removed.id }
        saveRules(list)
        return removed
    }

    fun describeRules(): String {
        val list = rules()
        if (list.isEmpty()) return "Você ainda não tem automações salvas."
        val state = if (paused()) "Estão pausadas." else "Estão ativas."
        val body = list.take(8).mapIndexed { index, r -> "${index + 1}. ${r.description}" }.joinToString("; ")
        val suffix = if (list.size > 8) "; e mais ${list.size - 8}." else "."
        return "Você tem ${list.size} automação${if (list.size == 1) "" else "ões"}. $body$suffix $state"
    }

    /** Returns rules that became due on this tick and atomically marks daily rules. */
    @Synchronized
    fun dueRules(): List<Rule> {
        if (paused()) return emptyList()
        return try {
            val snapshot = snapshot()
            val previousBattery = if (prefs.getBoolean(HAS_BATTERY, false)) prefs.getInt(LAST_BATTERY, -1) else null
            val previousHeadset = if (prefs.getBoolean(HAS_HEADSET, false)) prefs.getBoolean(LAST_HEADSET, false) else null
            val previousCharging = if (prefs.getBoolean(HAS_CHARGING, false)) prefs.getBoolean(LAST_CHARGING, false) else null

            val list = rules().toMutableList()
            val due = mutableListOf<Rule>()
            var changed = false
            for (i in list.indices) {
                val rule = list[i]
                if (!rule.enabled) continue
                val fire = when (rule.triggerKind) {
                    AutomationTriggerKind.DAILY_TIME -> {
                        val target = rule.triggerValue.toIntOrNull() ?: -1
                        AutomationDuePolicy.dailyDue(target, rule.lastFireToken, snapshot)
                    }
                    AutomationTriggerKind.BATTERY_BELOW -> {
                        val threshold = rule.triggerValue.toIntOrNull() ?: -1
                        threshold in 1..95 && AutomationDuePolicy.batteryCrossedBelow(previousBattery, snapshot.batteryPercent, threshold)
                    }
                    AutomationTriggerKind.HEADSET_CONNECTED ->
                        AutomationDuePolicy.becameConnected(previousHeadset, snapshot.headsetConnected)
                    AutomationTriggerKind.CHARGING_STARTED ->
                        AutomationDuePolicy.chargingStarted(previousCharging, snapshot.charging)
                }
                if (fire) {
                    due += rule
                    if (rule.triggerKind == AutomationTriggerKind.DAILY_TIME) {
                        list[i] = rule.copy(lastFireToken = snapshot.dayKey)
                        changed = true
                    }
                }
            }
            if (changed) saveRules(list)
            prefs.edit()
                .putBoolean(HAS_BATTERY, true)
                .putInt(LAST_BATTERY, snapshot.batteryPercent)
                .putBoolean(HAS_HEADSET, true)
                .putBoolean(LAST_HEADSET, snapshot.headsetConnected)
                .putBoolean(HAS_CHARGING, true)
                .putBoolean(LAST_CHARGING, snapshot.charging)
                .apply()
            due
        } catch (e: Exception) {
            Log.e(TAG, "Tick de automação falhou isoladamente", e)
            emptyList()
        }
    }

    private fun snapshot(): AutomationSnapshot {
        val cal = Calendar.getInstance()
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val battery = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(-1, 100)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headset = try {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } catch (_: Exception) { false }
        val batteryIntent = try { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return AutomationSnapshot(dayKey, minuteOfDay, battery, headset, charging)
    }

    /**
     * Learns only a tiny timing pattern for safe local actions. It never activates a rule by itself.
     * After the same safe action appears on three different days around the same hour, it returns a
     * suggested draft that Gama may offer to the user for explicit confirmation.
     */
    @Synchronized
    fun observeSafeHabit(command: String): AutomationDraft? {
        val normalized = AutomationCommandRouter.normalize(command)
        if (!habitEligible(normalized)) return null
        val now = Calendar.getInstance()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now.timeInMillis))
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val key = normalized.take(100)
        return try {
            val root = JSONObject(prefs.getString(HABITS, "{}").orEmpty().ifBlank { "{}" })
            val item = root.optJSONObject(key) ?: JSONObject()
            val days = item.optJSONArray("days") ?: JSONArray()
            val known = (0 until days.length()).mapNotNull { days.optString(it).takeIf(String::isNotBlank) }.toMutableSet()
            val oldHour = item.optInt("hour", hour)
            if (kotlin.math.abs(oldHour - hour) > 1) known.clear()
            known += day
            val clipped = known.toList().sorted().takeLast(5)
            val updated = JSONObject().apply {
                put("hour", hour)
                put("days", JSONArray(clipped))
                put("suggested", item.optBoolean("suggested", false))
            }
            root.put(key, updated)
            prefs.edit().putString(HABITS, root.toString()).apply()
            if (clipped.size >= 3 && !updated.optBoolean("suggested", false)) {
                updated.put("suggested", true)
                root.put(key, updated)
                prefs.edit().putString(HABITS, root.toString()).apply()
                AutomationDraft(
                    triggerKind = AutomationTriggerKind.DAILY_TIME,
                    triggerValue = (hour * 60 + now.get(Calendar.MINUTE)).toString(),
                    actionKind = AutomationActionKind.COMMAND,
                    action = normalized,
                    description = "todos os dias por volta de %02d:%02d: %s".format(Locale.US, hour, now.get(Calendar.MINUTE), normalized)
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Aprendizado de rotina falhou isoladamente", e)
            null
        }
    }

    private fun habitEligible(n: String): Boolean {
        if (n.contains("mensagem") || n.contains("notificacao") || n.contains("agenda") || n.contains("evento")) return false
        if (n.contains("bloque") || n.contains("desbloque") || n.contains("configurac") || n.contains("camera")) return false
        return n.startsWith("abra ") || n.startsWith("abre ") || n.startsWith("abrir ") ||
            n.contains("lanterna") || n.contains("volume") ||
            n.startsWith("pause") || n.startsWith("continue") || n.startsWith("retome")
    }
}
