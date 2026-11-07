package com.imperialreader.ui.common

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.imperialreader.R

object StartupStarAnimation {
    fun show(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density
        val overlay = FrameLayout(activity).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val sparkles = SparkleBurstView(activity)
        overlay.addView(
            sparkles,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val star = StarSpriteView(activity).apply {
            visibility = View.VISIBLE
            start(speed = 1.5f)
        }
        val starSize = (44 * density).toInt()
        overlay.addView(
            star,
            FrameLayout.LayoutParams(starSize, starSize, Gravity.CENTER)
        )

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        decor.addView(overlay, params)
        sparkles.start()
        overlay.postDelayed({
            star.stop()
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }, ANIMATION_DURATION_MS)
    }

    private const val ANIMATION_DURATION_MS = 3000L

    private class SparkleBurstView(activity: Activity) : View(activity) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = com.imperialreader.ui.theme.AppThemeManager.color(activity, com.google.android.material.R.attr.colorSecondary)
            style = Paint.Style.FILL
        }
        private var progress = 0f
        private var animator: ValueAnimator? = null

        fun start() {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANIMATION_DURATION_MS
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            val fade = (1f - progress).coerceIn(0f, 1f)
            for (index in 0 until SPARKLE_COUNT) {
                val angle = (Math.PI * 2 * index / SPARKLE_COUNT) - Math.PI / 2
                val travel = density * (12f + 32f * progress + (index % 3) * 4f)
                val x = centerX + kotlin.math.cos(angle).toFloat() * travel
                val y = centerY + kotlin.math.sin(angle).toFloat() * travel
                val radius = density * (1.5f + (index % 3) * 0.7f) * (0.45f + fade * 0.55f)
                paint.alpha = (255 * fade).toInt()
                drawSparkle(canvas, x, y, radius)
            }
        }

        private fun drawSparkle(canvas: Canvas, x: Float, y: Float, radius: Float) {
            val path = Path().apply {
                moveTo(x, y - radius)
                lineTo(x + radius * 0.28f, y - radius * 0.28f)
                lineTo(x + radius, y)
                lineTo(x + radius * 0.28f, y + radius * 0.28f)
                lineTo(x, y + radius)
                lineTo(x - radius * 0.28f, y + radius * 0.28f)
                lineTo(x - radius, y)
                lineTo(x - radius * 0.28f, y - radius * 0.28f)
                close()
            }
            canvas.drawPath(path, paint)
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        private companion object {
            const val SPARKLE_COUNT = 8
        }
    }
}
