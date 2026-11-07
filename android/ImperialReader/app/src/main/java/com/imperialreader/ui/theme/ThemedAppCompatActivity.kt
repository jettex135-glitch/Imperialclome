package com.imperialreader.ui.theme

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors

abstract class ThemedAppCompatActivity : AppCompatActivity() {
    private var appliedScheme: AppColorScheme? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val scheme = AppThemeManager.currentScheme(this)
        appliedScheme = scheme
        setTheme(scheme.style)
        if (scheme == AppColorScheme.DYNAMIC) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (appliedScheme != AppThemeManager.currentScheme(this)) {
            recreate()
        }
    }
}
