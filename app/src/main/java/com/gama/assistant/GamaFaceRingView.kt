package com.gama.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Red Gama energy/logo ring drawn around the live face preview. */
class GamaFaceRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = Color.rgb(239, 68, 68)
    }
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.0f)
        color = Color.argb(105, 239, 68, 68)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(248, 113, 113)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val base = size * 0.40f
        val t = (SystemClock.uptimeMillis() % 10_000L) / 10_000f
        val angle = t * 360f

        for (i in 0 until 8) {
            val radius = base + dp(7f) * i
            val box = RectF(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius,
            )
            val start = angle * (if (i % 2 == 0) 1f else -0.72f) + i * 31f
            val sweep = 55f + (i % 3) * 28f
            canvas.drawArc(
                box,
                start,
                sweep,
                false,
                if (i % 2 == 0) ringPaint else softPaint,
            )
        }

        for (i in 0 until 18) {
            val a = Math.toRadians((angle * 1.6f + i * 20f).toDouble())
            val r = base + dp(20f) + dp((i % 5) * 4f)
            val x = cx + cos(a).toFloat() * r
            val y = cy + sin(a).toFloat() * r
            canvas.drawCircle(
                x,
                y,
                dp(if (i % 4 == 0) 2.1f else 1.1f),
                dotPaint,
            )
        }

        postInvalidateOnAnimation()
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
