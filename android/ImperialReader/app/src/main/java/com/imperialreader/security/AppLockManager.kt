package com.imperialreader.security

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.imperialreader.App
import com.imperialreader.ui.main.MainActivity
import com.imperialreader.ui.security.AppLockActivity

class AppLockManager(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private var startedActivityCount = 0
    private var lockRequired = false
    private var lockActivityStarted = false
    private var pendingStartupActivity: MainActivity? = null

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount++
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is AppLockActivity) return

        val prefs = (application as App).prefs
        val backgroundedAt = prefs.appLockBackgroundAt
        if (backgroundedAt != 0L) {
            prefs.appLockBackgroundAt = 0L
            lockRequired = prefs.appLockEnabled &&
                System.currentTimeMillis() - backgroundedAt >= prefs.appLockTimeoutMillis
        }

        if (lockRequired) {
            pendingStartupActivity = activity as? MainActivity
            if (!lockActivityStarted && !activity.isFinishing && !activity.isDestroyed) {
                lockActivityStarted = true
                activity.startActivity(Intent(activity, AppLockActivity::class.java))
            }
        } else if (activity is MainActivity) {
            activity.showStartupAnimationIfPending()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
            (application as App).prefs.appLockBackgroundAt = System.currentTimeMillis()
        }
    }

    fun unlock() {
        lockRequired = false
        lockActivityStarted = false
        (application as App).prefs.appLockBackgroundAt = 0L
        pendingStartupActivity?.showStartupAnimationIfPending(STARTUP_ANIMATION_AFTER_UNLOCK_MS)
        pendingStartupActivity = null
    }

    private companion object {
        const val STARTUP_ANIMATION_AFTER_UNLOCK_MS = 250L
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
