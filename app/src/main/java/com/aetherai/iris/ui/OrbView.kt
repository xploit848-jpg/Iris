package com.aetherai.iris.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

private class OrbPulseUpdateListener(private val orb: OrbView) : ValueAnimator.AnimatorUpdateListener {
    override fun onAnimationUpdate(animation: ValueAnimator) {
        orb.pulseFraction = animation.animatedValue as Float
        orb.invalidate()
    }
}

private class OrbOuterRingUpdateListener(private val orb: OrbView) : ValueAnimator.AnimatorUpdateListener {
    override fun onAnimationUpdate(animation: ValueAnimator) {
        orb.outerRingAngle = animation.animatedValue as Float
        orb.invalidate()
    }
}

private class OrbInnerRingUpdateListener(private val orb: OrbView) : ValueAnimator.AnimatorUpdateListener {
    override fun onAnimationUpdate(animation: ValueAnimator) {
        orb.innerRingAngle = animation.animatedValue as Float
        orb.invalidate()
    }
}

class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var state: OrbState = OrbState.IDLE
        set(value) {
            field = value
            restartPulseForState(value)
            restartRingsForState(value)
        }

    var pulseFraction: Float = 0f
    var outerRingAngle: Float = 0f
    var innerRingAngle: Float = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var pulseAnimator: ValueAnimator? = null
    private var outerRingAnimator: ValueAnimator? = null
    private var innerRingAnimator: ValueAnimator? = null

    init {
        startPulseAnimator(1800L, 0.85f, 1.0f)
        startRingAnimators(1.0f)
    }

    private fun restartPulseForState(newState: OrbState) {
        when (newState) {
            OrbState.IDLE -> startPulseAnimator(2600L, 0.9f, 1.0f)
            OrbState.LISTENING -> startPulseAnimator(700L, 0.8f, 1.15f)
            OrbState.THINKING -> startPulseAnimator(450L, 0.75f, 1.05f)
            OrbState.SPEAKING -> startPulseAnimator(300L, 0.85f, 1.2f)
        }
    }

    private fun restartRingsForState(newState: OrbState) {
        val speedMultiplier = when (newState) {
            OrbState.IDLE -> 1.0f
            OrbState.LISTENING -> 1.8f
            OrbState.THINKING -> 2.6f
            OrbState.SPEAKING -> 2.2f
        }
        startRingAnimators(speedMultiplier)
    }

    private fun startPulseAnimator(durationMs: Long, from: Float, to: Float) {
        pulseAnimator?.cancel()
        val va = ValueAnimator.ofFloat(from, to)
        va.duration = durationMs
        va.repeatMode = ValueAnimator.REVERSE
        va.repeatCount = ValueAnimator.INFINITE
        va.addUpdateListener(OrbPulseUpdateListener(this))
        va.start()
        pulseAnimator = va
    }

    private fun startRingAnimators(speedMultiplier: Float) {
        outerRingAnimator?.cancel()
        innerRingAnimator?.cancel()

        val outer = ValueAnimator.ofFloat(0f, 360f)
        outer.duration = (6000L / speedMultiplier).toLong()
        outer.repeatCount = ValueAnimator.INFINITE
        outer.repeatMode = ValueAnimator.RESTART
        outer.addUpdateListener(OrbOuterRingUpdateListener(this))
        outer.start()
        outerRingAnimator = outer

        val inner = ValueAnimator.ofFloat(360f, 0f)
        inner.duration = (3400L / speedMultiplier).toLong()
        inner.repeatCount = ValueAnimator.INFINITE
        inner.repeatMode = ValueAnimator.RESTART
        inner.addUpdateListener(OrbInnerRingUpdateListener(this))
        inner.start()
        innerRingAnimator = inner
    }

    private fun colorForState(): Int {
        return when (state) {
            OrbState.IDLE -> Color.parseColor("#4C7EFF")
            OrbState.LISTENING -> Color.parseColor("#38E1C6")
            OrbState.THINKING -> Color.parseColor("#B084F5")
            OrbState.SPEAKING -> Color.parseColor("#FF6FA0")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(width, height) / 2f) * 0.42f
        val glowRadius = baseRadius * pulseFraction.coerceIn(0.5f, 1.3f)
        val color = colorForState()

        val gradient = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(color, Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = gradient
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        drawTickRing(canvas, cx, cy, baseRadius * 1.55f, outerRingAngle, 16, 14f, 4.5f, color, 200)
        drawTickRing(canvas, cx, cy, baseRadius * 1.15f, innerRingAngle, 24, 8f, 3f, color, 150)
    }

    private fun drawTickRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        rotationDegrees: Float,
        tickCount: Int,
        tickLength: Float,
        strokeWidth: Float,
        color: Int,
        alpha: Int
    ) {
        ringPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        ringPaint.strokeWidth = strokeWidth

        canvas.save()
        canvas.rotate(rotationDegrees, cx, cy)
        val step = 360f / tickCount
        for (i in 0 until tickCount) {
            val angle = Math.toRadians((i * step).toDouble())
            val startX = cx + (radius * Math.cos(angle)).toFloat()
            val startY = cy + (radius * Math.sin(angle)).toFloat()
            val endX = cx + ((radius + tickLength) * Math.cos(angle)).toFloat()
            val endY = cy + ((radius + tickLength) * Math.sin(angle)).toFloat()
            canvas.drawLine(startX, startY, endX, endY, ringPaint)
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        outerRingAnimator?.cancel()
        innerRingAnimator?.cancel()
    }
}
