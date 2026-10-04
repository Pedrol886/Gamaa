package com.gama.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/** Watches only the isolated :gama_brain process. It never starts the brain until requested. */
class BrainProcessSupervisor(
    context: Context,
    private val onDeath: () -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var bound = false
    private var binder: IBinder? = null
    private var deathDelivered = false

    private val deathRecipient = IBinder.DeathRecipient { deliverDeath("binder") }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            deathDelivered = false
            binder = service
            bound = true
            runCatching { service?.linkToDeath(deathRecipient, 0) }
                .onFailure { deliverDeath("link") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            deliverDeath("disconnect")
        }

        override fun onBindingDied(name: ComponentName?) {
            deliverDeath("binding-died")
        }

        override fun onNullBinding(name: ComponentName?) {
            deliverDeath("null-binding")
        }
    }

    /** Call only after a real brain request has started the remote service. */
    fun bindStartedBrain() {
        if (bound) return
        val intent = Intent(appContext, GamaBrainProcessService::class.java)
        runCatching {
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.w("GamaBrainSupervisor", "brain bind failed: ${it.javaClass.simpleName}")
        }
    }

    private fun deliverDeath(source: String) {
        if (deathDelivered) return
        deathDelivered = true
        Log.w("GamaBrainSupervisor", "brain unavailable: $source")
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        bound = false
        onDeath()
    }

    override fun close() {
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        if (bound) runCatching { appContext.unbindService(connection) }
        bound = false
        deathDelivered = false
    }
}
