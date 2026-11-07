package com.imperialreader.ui.theme

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.imperialreader.R
import com.imperialreader.util.dp

class ThemeEditorActivity : ThemedAppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppThemeManager.color(this@ThemeEditorActivity, android.R.attr.colorBackground))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "Appearance"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        content.addView(label("Theme mode", 20f))
        content.addView(description("Follow your device setting or choose light or dark mode."))
        content.addView(modeSelector())
        content.addView(label("Color scheme", 20f).apply {
            setPadding(0, dp(24), 0, dp(4))
        })
        content.addView(description("Themes use Material color roles. Cover artwork and reader pages are never tinted."))
        AppColorScheme.entries.forEach { scheme ->
            content.addView(schemeCard(scheme))
        }
        content.addView(description("Device colors require Android 12 or later. Older devices use Imperial colors.").apply {
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        return root
    }

    private fun modeSelector(): View {
        val selected = AppThemeManager.currentMode(this)
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val buttons = AppThemeMode.entries.map { mode ->
            MaterialButton(this).apply {
                id = View.generateViewId()
                text = mode.label
                isChecked = mode == selected
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                group.addView(this)
            }.let { mode to it.id }
        }.toMap()
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                AppThemeMode.entries.firstOrNull { buttons[it] == checkedId }?.let {
                    AppThemeManager.setMode(this, it)
                }
            }
        }
        return group
    }

    private fun schemeCard(scheme: AppColorScheme): View {
        val selected = scheme == AppThemeManager.currentScheme(this)
        val preview = AppThemeManager.previewColors(this, scheme)
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            setCardBackgroundColor(preview.surface)
            strokeWidth = dp(if (selected) 2 else 1)
            strokeColor = preview.primary
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!selected) {
                    AppThemeManager.setScheme(this@ThemeEditorActivity, scheme)
                    recreate()
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val title = TextView(this).apply {
            text = if (selected) "${scheme.label}  ·  SELECTED" else scheme.label
            textSize = 17f
            setTextColor(preview.text)
        }
        body.addView(title)
        body.addView(TextView(this).apply {
            text = "Sample text and controls"
            textSize = 14f
            setTextColor(preview.text)
            setPadding(0, dp(6), 0, dp(10))
        })
        val swatches = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(preview.primary, preview.surface, preview.text).forEach { color ->
            swatches.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(1), Color.GRAY)
                }
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(8)
                }
            })
        }
        body.addView(swatches)
        card.addView(body)
        return card
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(AppThemeManager.color(this@ThemeEditorActivity, com.google.android.material.R.attr.colorOnSurface))
    }

    private fun description(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(AppThemeManager.color(this@ThemeEditorActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(0, dp(4), 0, dp(8))
    }
}
