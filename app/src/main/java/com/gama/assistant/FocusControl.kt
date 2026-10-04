package com.gama.assistant

import android.app.NotificationManager
import android.content.Context

/** Foco do Gama funciona sem acesso especial; interrupções Android só mudam com acesso do usuário. */
object FocusControl {
    private const val STORE = "gama_focus_dnd_v1"
    fun enable(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) {
            return "Protocolo de foco ativado no Gama. As notificações do Android não foram alteradas; autorize 'Não perturbe' nos ajustes se desejar."
        }
        return try {
            val previous = manager.currentInterruptionFilter
            if (previous == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                "Protocolo de foco ativado no Gama. O Android já estava em Não perturbe."
            } else {
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                    context.getSharedPreferences(STORE, 0).edit().putInt("previous", previous).apply()
                    "Protocolo de foco ativado no Gama. Não perturbe também está ativado no Android, com as exceções configuradas por você."
                } else "Protocolo de foco ativado no Gama. O Android não confirmou a alteração das notificações."
            }
        } catch (_: Exception) {
            "Protocolo de foco ativado no Gama. Não consegui alterar o Não perturbe do Android."
        }
    }
    fun disable(context: Context): String {
        val prefs = context.getSharedPreferences(STORE, 0)
        val previous = prefs.getInt("previous", NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        prefs.edit().remove("previous").apply()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (previous == NotificationManager.INTERRUPTION_FILTER_UNKNOWN || !manager.isNotificationPolicyAccessGranted) {
            return "Protocolo de foco desativado no Gama. Não alterei o Não perturbe do Android."
        }
        return try {
            if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                manager.setInterruptionFilter(previous)
                if (manager.currentInterruptionFilter == previous)
                    "Protocolo de foco desativado no Gama. Restaurei o modo anterior de notificações do Android."
                else "Protocolo de foco desativado no Gama. O Android não confirmou a restauração das notificações."
            } else "Protocolo de foco desativado no Gama. Mantive o modo de notificações alterado por você."
        } catch (_: Exception) {
            "Protocolo de foco desativado no Gama. Verifique o Não perturbe do Android nos ajustes."
        }
    }
}
