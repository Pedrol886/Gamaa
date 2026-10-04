package com.gama.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Gama Red Core visual.
 *
 * The supplied Gama emblem is the single visual identity used by the floating
 * assistant, lock-screen conversation and intro. During TTS the lower jaw is
 * drawn independently and moves with a speech-like cadence. No microphone
 * audio is needed, so the animation cannot feed Gama's own TTS back into Vosk.
 */
class GamaEnergyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.LISTENING
        set(value) {
            field = value
            invalidate()
        }

    private val logo: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.gama_red_core)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var phase = 0f
    private var released = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1180L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || logo.width <= 0 || logo.height <= 0) return

        val minSize = minOf(width, height).toFloat()
        val t = phase * (PI * 2.0)
        val speakingWave = (
            abs(sin(t * 3.85)) * 0.58 +
                abs(sin(t * 6.35 + 0.7)) * 0.30 +
                abs(sin(t * 9.15 + 1.8)) * 0.12
            ).toFloat().coerceIn(0f, 1f)

        val pulse = when (mode) {
            Mode.LISTENING -> (0.50 + 0.50 * sin(t * 1.35)).toFloat()
            Mode.THINKING -> (0.50 + 0.50 * sin(t * 2.15)).toFloat()
            Mode.SPEAKING -> speakingWave
        }

        val cx = width / 2f
        val cy = height / 2f

        // Red aura: quiet while listening, stronger while thinking/speaking.
        val auraRadius = minSize * (0.40f + 0.018f * pulse)
        val auraAlpha = when (mode) {
            Mode.LISTENING -> 44
            Mode.THINKING -> 72
            Mode.SPEAKING -> 92
        }
        glowPaint.color = Color.argb(auraAlpha, 230, 20, 34)
        glowPaint.setShadowLayer(minSize * 0.13f, 0f, 0f, Color.rgb(175, 8, 20))
        canvas.drawCircle(cx, cy, auraRadius, glowPaint)
        glowPaint.clearShadowLayer()

        val availableW = width * 0.91f
        val availableH = height * 0.91f
        val scale = minOf(availableW / logo.width, availableH / logo.height)
        val drawW = logo.width * scale
        val drawH = logo.height * scale
        val left = cx - drawW / 2f
        val top = cy - drawH / 2f

        // The original emblem stays intact except for the central lower jaw.
        // Percentages are tied to the supplied art and avoid changing its design.
        val jawLeftPx = (logo.width * 0.315f).toInt()
        val jawRightPx = (logo.width * 0.685f).toInt()
        val jawTopPx = (logo.height * 0.665f).toInt()

        val jawLeft = left + jawLeftPx * scale
        val jawRight = left + jawRightPx * scale
        val jawTop = top + jawTopPx * scale

        // Upper body.
        drawRegion(
            canvas,
            Rect(0, 0, logo.width, jawTopPx),
            RectF(left, top, left + drawW, jawTop),
        )

        // Lower side pieces stay fixed so only the mouth/jaw talks.
        drawRegion(
            canvas,
            Rect(0, jawTopPx, jawLeftPx, logo.height),
            RectF(left, jawTop, jawLeft, top + drawH),
        )
        drawRegion(
            canvas,
            Rect(jawRightPx, jawTopPx, logo.width, logo.height),
            RectF(jawRight, jawTop, left + drawW, top + drawH),
        )

        val open = if (mode == Mode.SPEAKING) {
            minSize * (0.010f + 0.050f * speakingWave)
        } else {
            minSize * (0.0025f + 0.004f * pulse)
        }
        val stretch = if (mode == Mode.SPEAKING) 1f + speakingWave * 0.075f else 1f
        val baseJawHeight = (logo.height - jawTopPx) * scale
        val jawDest = RectF(
            jawLeft,
            jawTop + open,
            jawRight,
            jawTop + open + baseJawHeight * stretch,
        )
        drawRegion(
            canvas,
            Rect(jawLeftPx, jawTopPx, jawRightPx, logo.height),
            jawDest,
        )

        // Thin red state ring around the emblem. It keeps the old state
        // readability without bringing the old orb design back.
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeWidth = (minSize * 0.010f).coerceAtLeast(2f)
        accentPaint.color = Color.argb(
            when (mode) {
                Mode.LISTENING -> 105
                Mode.THINKING -> 150
                Mode.SPEAKING -> 185
            },
            248,
            42,
            52,
        )
        accentPaint.setShadowLayer(minSize * 0.045f, 0f, 0f, Color.rgb(239, 30, 45))
        val ringRadius = minSize * (0.445f + 0.008f * pulse)
        canvas.drawCircle(cx, cy, ringRadius, accentPaint)
        accentPaint.clearShadowLayer()
    }

    private fun drawRegion(canvas: Canvas, src: Rect, dst: RectF) {
        if (src.width() <= 0 || src.height() <= 0 || dst.width() <= 0f || dst.height() <= 0f) return
        bitmapPaint.alpha = 255
        canvas.drawBitmap(logo, src, dst, bitmapPaint)
    }

    fun release() {
        if (!released) {
            released = true
            animator.cancel()
        }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
