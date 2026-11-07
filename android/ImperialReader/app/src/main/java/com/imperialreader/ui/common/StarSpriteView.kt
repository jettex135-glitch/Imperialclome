package com.imperialreader.ui.common

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import com.imperialreader.R

/** The little gold star that spins and bounces while searching or updating. */
class StarSpriteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private var animator: AnimatorSet? = null

    init {
        setImageResource(R.drawable.ic_star_sprite)
        visibility = View.GONE
    }

    fun start(speed: Float = 1f) {
        visibility = View.VISIBLE
        if (animator?.isRunning == true) return
        val durationScale = speed.coerceIn(0.5f, 2f)
        val spin = ObjectAnimator.ofFloat(this, ROTATION, 0f, 360f).apply {
            duration = (1400 / durationScale).toLong()
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        val bounce = ObjectAnimator.ofFloat(this, TRANSLATION_Y, 0f, -14f).apply {
            duration = (500 / durationScale).toLong()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        val pulseX = ObjectAnimator.ofFloat(this, SCALE_X, 1f, 1.25f).apply {
            duration = (500 / durationScale).toLong()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        val pulseY = ObjectAnimator.ofFloat(this, SCALE_Y, 1f, 1.25f).apply {
            duration = (500 / durationScale).toLong()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        animator = AnimatorSet().apply {
            playTogether(listOf<Animator>(spin, bounce, pulseX, pulseY))
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        rotation = 0f
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        visibility = View.GONE
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
