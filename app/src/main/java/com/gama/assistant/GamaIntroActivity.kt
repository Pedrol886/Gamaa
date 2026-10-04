package com.gama.assistant

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * First complete setup ceremony.
 *
 * It is intentionally an Activity instead of an overlay so the same presentation
 * can be shown whether Android is currently locked or unlocked. It never unlocks
 * the device and it never displays private information.
 */
class GamaIntroActivity : Activity(), TextToSpeech.OnInitListener {
    companion object {
        const val PREFS = "gama_first_run_v35"
        const val KEY_COMPLETE = "intro_v60_complete"
        const val KEY_STARTED_AT = "intro_v60_started_at"
        private const val GREETING_ID = "gama-intro-greeting"
        private const val CAPABILITIES_ID = "gama-intro-capabilities"
        private const val END_ID = "gama-intro-end"
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var energy: GamaEnergyView
    private lateinit var formation: GamaParticleFormationView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var formationReady = false
    private var speechStarted = false
    private var finishedNormally = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_COMPLETE, false)) {
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
        window.navigationBarColor = Color.BLACK
        hideSystemBars()

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()

        AssistantRuntime.service?.beginIntroPresentation()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(8, 2, 5))
        }

        energy = GamaEnergyView(this).apply {
            mode = GamaEnergyView.Mode.SPEAKING
            alpha = 0f
        }
        root.addView(
            energy,
            FrameLayout.LayoutParams(dp(310), dp(310), Gravity.CENTER)
        )

        formation = GamaParticleFormationView(this).apply {
            onProgress = { progress ->
                val reveal = ((progress - 0.72f) / 0.25f).coerceIn(0f, 1f)
                energy.alpha = reveal * reveal
            }
            onComplete = {
                formationReady = true
                energy.alpha = 1f
                formation.visibility = View.GONE
                ui.postDelayed({ maybeStartSpeech() }, 260L)
            }
        }
        root.addView(
            formation,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
        tts = TextToSpeech(this, this)
        ui.postDelayed({ formation.start() }, 300L)

        // Absolute safety net. The ceremony must never trap the user on screen.
        ui.postDelayed({
            if (!finishedNormally && !isFinishing) completeAndClose()
        }, 42_000L)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            if (formationReady) ui.postDelayed({ completeAndClose() }, 1400L)
            return
        }

        ttsReady = true
        try {
            tts?.language = Locale("pt", "BR")
            GamaVoice.apply(this, tts)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    when (utteranceId) {
                        GREETING_ID -> ui.postDelayed({ speakCapabilities() }, 260L)
                        CAPABILITIES_ID -> ui.postDelayed({ speakClosing() }, 220L)
                        END_ID -> ui.postDelayed({ completeAndClose() }, 650L)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    ui.postDelayed({ completeAndClose() }, 450L)
                }
            })
        } catch (_: Exception) {
            ttsReady = false
        }
        maybeStartSpeech()
    }

    private fun maybeStartSpeech() {
        if (speechStarted || !formationReady) return
        if (!ttsReady) {
            ui.postDelayed({
                if (!speechStarted) completeAndClose()
            }, 1800L)
            return
        }
        speechStarted = true
        energy.mode = GamaEnergyView.Mode.SPEAKING

        val identity = OwnerIdentity.address(this)
            ?: OwnerIdentity.name(this)?.let { "Senhor $it" }
            ?: "Senhor"

        val greeting =
            "Olá, $identity. Eu sou o Gama, seu assistente de inteligência artificial."

        val result = try {
            tts?.speak(greeting, TextToSpeech.QUEUE_FLUSH, null, GREETING_ID)
        } catch (_: Exception) {
            TextToSpeech.ERROR
        }
        if (result == null || result == TextToSpeech.ERROR) {
            ui.postDelayed({ completeAndClose() }, 650L)
        }
    }

    private fun speakCapabilities() {
        if (isFinishing || !ttsReady) return
        energy.mode = GamaEnergyView.Mode.SPEAKING
        val capabilities =
            "Posso abrir aplicativos, consultar sua agenda e suas notificações, " +
                "informar horas e bateria, controlar algumas funções do celular, " +
                "pesquisar informações, ajudar com mensagens no WhatsApp e manter " +
                "uma conversa contínua com você. Quando um pedido envolver dados " +
                "privados, eu confirmarei sua identidade pelo próprio Android."
        try {
            tts?.speak(capabilities, TextToSpeech.QUEUE_ADD, null, CAPABILITIES_ID)
        } catch (_: Exception) {
            completeAndClose()
        }
    }

    private fun speakClosing() {
        if (isFinishing || !ttsReady) return
        val closing = "Quando precisar de mim, diga Gama."
        try {
            tts?.speak(closing, TextToSpeech.QUEUE_ADD, null, END_ID)
        } catch (_: Exception) {
            completeAndClose()
        }
    }

    private fun completeAndClose() {
        if (finishedNormally) return
        finishedNormally = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_COMPLETE, true)
            .remove(KEY_STARTED_AT)
            .apply()
        AssistantRuntime.service?.endIntroPresentation()
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        if (::formation.isInitialized) formation.release()
        if (::energy.isInitialized) energy.release()
        finishAndRemoveTask()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.decorView.windowInsetsController?.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
            } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A apresentação é curta e acontece uma única vez.
        // O usuário nunca fica preso porque existe um timeout absoluto.
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (!finishedNormally) AssistantRuntime.service?.endIntroPresentation()
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        if (::formation.isInitialized) formation.release()
        if (::energy.isInitialized) energy.release()
        super.onDestroy()
    }
}

/**
 * Full-screen particle field used only during the first-ready presentation.
 * Hundreds of tiny orange points travel from the entire display toward the
 * exact family of circular radii used by GamaEnergyView. The energy view is
 * then revealed underneath, so the final shape is literally the normal Gama
 * speaking animation rather than a look-alike replacement.
 */
class GamaParticleFormationView(context: android.content.Context) : View(context) {
    private data class Particle(
        val startX: Float,
        val startY: Float,
        val targetX: Float,
        val targetY: Float,
        val radius: Float,
        val phase: Float,
        val curl: Float,
        val depth: Float,
        val brightness: Int
    )

    var onProgress: ((Float) -> Unit)? = null
    var onComplete: (() -> Unit)? = null

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val particles = ArrayList<Particle>(336)
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var generatedWidth = 0
    private var generatedHeight = 0

    fun start() {
        animator?.cancel()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4100L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                onProgress?.invoke(progress)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (progress >= 0.995f) onComplete?.invoke()
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (generatedWidth == w && generatedHeight == h && particles.isNotEmpty()) return
        generatedWidth = w
        generatedHeight = h
        buildParticles(w, h)
    }

    private fun buildParticles(w: Int, h: Int) {
        particles.clear()
        val random = Random(6060)
        val cx = w / 2f
        val cy = h / 2f
        val size = minOf(w, h).toFloat()
        val density = resources.displayMetrics.density

        repeat(384) { index ->
            // Start across the whole screen, with extra density close to the borders.
            val edge = index % 5
            val sx: Float
            val sy: Float
            when (edge) {
                0 -> { sx = random.nextFloat() * w; sy = -random.nextFloat() * h * .08f }
                1 -> { sx = random.nextFloat() * w; sy = h + random.nextFloat() * h * .08f }
                2 -> { sx = -random.nextFloat() * w * .08f; sy = random.nextFloat() * h }
                3 -> { sx = w + random.nextFloat() * w * .08f; sy = random.nextFloat() * h }
                else -> {
                    sx = random.nextFloat() * w
                    sy = random.nextFloat() * h
                }
            }

            val ring = index % 5
            val angle = (index.toDouble() / 336.0) * PI * 2.0 + ring * 0.31
            val base = size * (0.29f + ring * 0.035f)
            val organic = sin(angle * (3.0 + ring) + index * 0.19) * size * 0.0105
            val targetRadius = base + organic.toFloat()
            val tx = cx + cos(angle).toFloat() * targetRadius
            val ty = cy + sin(angle).toFloat() * targetRadius
            val depth = 0.45f + random.nextFloat() * 0.90f

            particles += Particle(
                startX = sx,
                startY = sy,
                targetX = tx,
                targetY = ty,
                radius = (0.70f + random.nextFloat() * 1.55f) * density,
                phase = random.nextFloat() * (PI * 2.0).toFloat(),
                curl = (random.nextFloat() * 2f - 1f) * size * (0.23f + 0.10f * depth),
                depth = depth,
                brightness = 150 + random.nextInt(106)
            )
        }
    }

    private fun eased(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return 1f - (1f - v) * (1f - v) * (1f - v)
    }

    private fun position(particle: Particle, localProgress: Float): Pair<Float, Float> {
        val lp = localProgress.coerceIn(0f, 1f)
        val le = eased(lp)
        val dx = particle.targetX - particle.startX
        val dy = particle.targetY - particle.startY
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val nx = -dy / length
        val ny = dx / length

        // Wide vortex at the beginning, tightening into the exact energy rings.
        val vortexEnvelope = sin((lp * PI).toDouble()).toFloat() * (1f - lp * .38f)
        val swirl = sin((lp * PI * 2.15 + particle.phase).toDouble()).toFloat() *
            particle.curl * vortexEnvelope
        val inwardBend = sin((lp * PI).toDouble()).toFloat() * particle.curl * .18f

        return Pair(
            particle.startX + dx * le + nx * swirl + (dx / length) * inwardBend,
            particle.startY + dy * le + ny * swirl + (dy / length) * inwardBend
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || particles.isEmpty()) return

        val p = progress.coerceIn(0f, 1f)
        val cx = width / 2f
        val cy = height / 2f
        val size = minOf(width, height).toFloat()

        // A subtle orange atmosphere grows as the particles acquire a common center.
        val hazePulse = sin((p * PI).toDouble()).toFloat().coerceAtLeast(0f)
        hazePaint.color = Color.argb((14 + 56 * hazePulse).toInt(), 239, 35, 52)
        canvas.drawCircle(cx, cy, size * (0.06f + 0.20f * eased(p)), hazePaint)

        // During the final third, incomplete energy arcs appear underneath the particles.
        if (p > .58f) {
            val reveal = ((p - .58f) / .42f).coerceIn(0f, 1f)
            val sweep = 40f + 310f * reveal
            for (ring in 0 until 5) {
                val r = size * (.29f + ring * .035f)
                arcPaint.strokeWidth = size * (.0030f + (4 - ring) * .00035f)
                arcPaint.color = Color.argb(
                    (30 + 105 * reveal - ring * 7).toInt().coerceIn(12, 145),
                    255, 139, 39
                )
                val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, -92f + ring * 17f, sweep, false, arcPaint)
            }
        }

        for ((index, particle) in particles.withIndex()) {
            val localDelay = (index % 29) * 0.0023f
            val lp = ((p - localDelay) / (1f - localDelay)).coerceIn(0f, 1f)
            val (x, y) = position(particle, lp)
            val previous = (lp - (0.018f + 0.012f * particle.depth)).coerceAtLeast(0f)
            val (px, py) = position(particle, previous)
            val le = eased(lp)

            val alpha = (38 + 217 * le).toInt().coerceIn(0, particle.brightness)
            val radius = particle.radius * particle.depth * (0.68f + 0.42f * le)

            trailPaint.strokeWidth = (radius * .62f).coerceAtLeast(.7f)
            trailPaint.color = Color.argb((alpha * .26f).toInt().coerceIn(0, 70), 245, 38, 55)
            canvas.drawLine(px, py, x, y, trailPaint)

            glowPaint.color = Color.argb((alpha / 5).coerceAtLeast(5), 255, 48, 64)
            canvas.drawCircle(x, y, radius * (3.0f + .9f * le), glowPaint)

            val green = 118 + (index % 5) * 9
            pointPaint.color = Color.argb(alpha, 255, green, 48)
            canvas.drawCircle(x, y, radius, pointPaint)
        }

        // Small convergence flash, then the real GamaEnergyView takes over.
        if (p > .88f) {
            val f = ((p - .88f) / .12f).coerceIn(0f, 1f)
            hazePaint.color = Color.argb((105 * sin((f * PI).toDouble())).toInt().coerceIn(0, 105), 255, 196, 72)
            canvas.drawCircle(cx, cy, size * (.07f + .13f * f), hazePaint)
        }
    }

    fun release() {
        animator?.cancel()
        animator = null
        onProgress = null
        onComplete = null
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
