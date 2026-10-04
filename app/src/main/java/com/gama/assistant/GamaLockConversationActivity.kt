package com.gama.assistant

import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual de conversa que aparece somente sobre a tela de bloqueio.
 * Ele não desbloqueia o Android e não mostra conteúdo privado.
 */
class GamaLockConversationActivity : Activity() {
    companion object {
        const val ACTION_LISTENING = "com.gama.assistant.LOCK_LISTENING"
        const val ACTION_THINKING = "com.gama.assistant.LOCK_THINKING"
        const val ACTION_SPEAKING = "com.gama.assistant.LOCK_SPEAKING"
        const val ACTION_CLOSE = "com.gama.assistant.LOCK_CLOSE"
    }

    private lateinit var ring: GamaEnergyView
    private lateinit var status: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_LISTENING -> setState(GamaEnergyView.Mode.LISTENING, "Estou ouvindo")
                ACTION_THINKING -> setState(GamaEnergyView.Mode.THINKING, "Pensando")
                ACTION_SPEAKING -> setState(GamaEnergyView.Mode.SPEAKING, "Falando")
                ACTION_CLOSE, Intent.ACTION_USER_PRESENT -> finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isLocked()) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(8, 2, 5))
        }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val name = TextView(this).apply {
            text = "GAMA"
            textSize = 16f
            letterSpacing = 0.22f
            setTextColor(Color.rgb(255, 48, 68))
            gravity = Gravity.CENTER
        }
        center.addView(name, LinearLayout.LayoutParams(-1, dp(42)))

        ring = GamaEnergyView(this)
        center.addView(ring, LinearLayout.LayoutParams(dp(320), dp(320)))

        status = TextView(this).apply {
            text = "Estou ouvindo"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        center.addView(status, LinearLayout.LayoutParams(-1, dp(58)))

        val hint = TextView(this).apply {
            text = "Conversa contínua • diga ‘pode descansar’ para encerrar"
            textSize = 12f
            setTextColor(Color.rgb(198, 132, 141))
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
        }
        center.addView(hint, LinearLayout.LayoutParams(-1, dp(48)))

        root.addView(
            center,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)
        )
        setContentView(root)

        val filter = IntentFilter().apply {
            addAction(ACTION_LISTENING)
            addAction(ACTION_THINKING)
            addAction(ACTION_SPEAKING)
            addAction(ACTION_CLOSE)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }

        setState(GamaEnergyView.Mode.LISTENING, "Estou ouvindo")
    }

    override fun onResume() {
        super.onResume()
        if (!isLocked()) finishAndRemoveTask()
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (::ring.isInitialized) ring.release()
        super.onDestroy()
    }

    private fun setState(mode: GamaEnergyView.Mode, label: String) {
        if (!::ring.isInitialized) return
        ring.mode = mode
        if (::status.isInitialized) status.text = label
    }

    private fun isLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isDeviceLocked

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

