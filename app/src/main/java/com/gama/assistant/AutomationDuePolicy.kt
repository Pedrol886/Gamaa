package com.gama.assistant

data class AutomationSnapshot(
    val dayKey: String,
    val minuteOfDay: Int,
    val batteryPercent: Int,
    val headsetConnected: Boolean,
    val charging: Boolean
)

object AutomationDuePolicy {
    fun dailyDue(targetMinute: Int, lastFireToken: String, snapshot: AutomationSnapshot): Boolean =
        targetMinute in 0..1439 &&
            snapshot.minuteOfDay in targetMinute..(targetMinute + 1).coerceAtMost(1439) &&
            lastFireToken != snapshot.dayKey

    fun batteryCrossedBelow(previous: Int?, current: Int, threshold: Int): Boolean =
        previous != null && previous >= threshold && current in 0 until threshold

    fun becameConnected(previous: Boolean?, current: Boolean): Boolean =
        previous == false && current

    fun chargingStarted(previous: Boolean?, current: Boolean): Boolean =
        previous == false && current
}
