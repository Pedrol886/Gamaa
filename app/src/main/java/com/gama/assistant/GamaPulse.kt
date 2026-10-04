package com.gama.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.CalendarContract
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Avisos opcionais, sem escuta extra de microfone, consulta de rede nem monitoramento oculto. */
object GamaPulse {
    private const val PREFS = "gama_proactive_v1"
    const val ACTION_CHECK = "com.gama.assistant.PROACTIVE_CHECK"
    const val CHANNEL = "gama_reminders"
    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, 0).getBoolean("enabled", false)
    fun toggle(ctx: Context, enable: Boolean): String {
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", enable).apply()
        if (enable) schedule(ctx) else cancel(ctx)
        return if (enable) "Avisos inteligentes ativados. O Gama verificará periodicamente bateria e próximos compromissos, com seu consentimento e as permissões do Android."
            else "Avisos inteligentes desativados. Nenhuma verificação periódica será agendada pelo Gama."
    }
    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 41, Intent(ctx, GamaPulseReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        // Inexato por economia de bateria. Pode atrasar sob Doze do Android.
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(30),
            TimeUnit.MINUTES.toMillis(30), pending(ctx))
    }
    private fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
    }
    fun check(ctx: Context) {
        if (!enabled(ctx)) return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        checkBattery(ctx)
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) checkCalendar(ctx)
    }
    private fun checkBattery(ctx: Context) {
        val percent = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent !in 0..15) return
        val prefs = ctx.getSharedPreferences(PREFS, 0)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_battery_notice", 0L) < TimeUnit.HOURS.toMillis(12)) return
        prefs.edit().putLong("last_battery_notice", now).apply()
        notify(ctx, 200, "Bateria em $percent%", "Sugiro carregar o celular ou pausar a escuta constante para conservar energia.")
    }
    private fun checkCalendar(ctx: Context) {
        val now = System.currentTimeMillis()
        val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN)
        try {
            CalendarContract.Instances.query(ctx.contentResolver, proj, now, now + TimeUnit.HOURS.toMillis(2))?.use { cur ->
                while (cur.moveToNext()) {
                    val begins = cur.getLong(1)
                    if (begins - now > TimeUnit.MINUTES.toMillis(65)) continue
                    val key = "$begins:${cur.getString(0)?.take(40).orEmpty()}"
                    val prefs = ctx.getSharedPreferences(PREFS, 0)
                    if (key == prefs.getString("last_event_notice", "")) break
                    prefs.edit().putString("last_event_notice", key).apply()
                    notify(ctx, 201, "Compromisso próximo", "Há um evento do calendário nas próximas 65 minutos. Abra sua agenda para conferir.")
                    break
                }
            }
        } catch (_: Exception) { /* Sem acesso, evento, ou calendário: nada é anunciado. */ }
    }
    private fun notify(ctx: Context, id: Int, title: String, message: String) {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Avisos do Gama", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = PendingIntent.getActivity(ctx, 42, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
            .setContentText(message).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(launch).setAutoCancel(true).build())
    }
}

class GamaPulseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (GamaPulse.enabled(context)) GamaPulse.schedule(context)
            return
        }
        if (intent?.action != GamaPulse.ACTION_CHECK || !GamaPulse.enabled(context)) return
        val result = goAsync()
        thread(name = "gama-periodic-check") {
            try { GamaPulse.check(context.applicationContext) } finally { result.finish() }
        }
    }
}
