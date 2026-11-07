package com.imperialreader.ui.more

import android.app.KeyguardManager
import android.os.Bundle
import android.webkit.CookieManager
import androidx.biometric.BiometricManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.lifecycle.lifecycleScope
import com.imperialreader.App
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.imperialreader.R
import com.imperialreader.data.cover.AutomaticCoverRefreshScheduler
import com.imperialreader.data.update.AutomaticAppUpdateScheduler
import com.imperialreader.data.update.LibraryUpdateScheduler
import com.imperialreader.util.toast
import kotlinx.coroutines.launch

class SettingsActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()

            findPreference<Preference>("version")?.summary = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "1.0.0"

            val appLockTimeout = findPreference<ListPreference>("app_lock_timeout")
            appLockTimeout?.isEnabled = findPreference<SwitchPreferenceCompat>("app_lock_enabled")?.isChecked == true
            findPreference<SwitchPreferenceCompat>("app_lock_enabled")?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !hasDeviceAuthentication(ctx)) {
                    ctx.toast("Set up a screen lock or biometric on your device before enabling app lock")
                    false
                } else {
                    appLockTimeout?.isEnabled = newValue == true
                    true
                }
            }

            findPreference<SwitchPreferenceCompat>("automatic_cover_refresh")?.setOnPreferenceChangeListener { _, newValue ->
                AutomaticCoverRefreshScheduler.onSettingChanged(ctx, enabled = newValue == true)
                true
            }
            val libraryUpdateFrequency = findPreference<ListPreference>("library_update_frequency")
            libraryUpdateFrequency?.isEnabled = findPreference<SwitchPreferenceCompat>("refresh_on_start")?.isChecked == true
            findPreference<SwitchPreferenceCompat>("refresh_on_start")?.setOnPreferenceChangeListener { _, newValue ->
                libraryUpdateFrequency?.isEnabled = newValue == true
                LibraryUpdateScheduler.onSettingChanged(
                    ctx,
                    enabled = newValue == true,
                    intervalMinutes = libraryUpdateFrequency?.value?.toLongOrNull() ?: 0L
                )
                true
            }
            libraryUpdateFrequency?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = findPreference<SwitchPreferenceCompat>("refresh_on_start")?.isChecked == true
                val interval = (newValue as? String)?.toLongOrNull() ?: 0L
                LibraryUpdateScheduler.onSettingChanged(ctx, enabled, interval)
                true
            }
            val automaticAppUpdateFrequency = findPreference<ListPreference>("automatic_app_update_frequency")
            val automaticAppUpdates = findPreference<SwitchPreferenceCompat>("automatic_app_updates")
            automaticAppUpdateFrequency?.isEnabled = automaticAppUpdates?.isChecked == true
            automaticAppUpdates?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue == true
                automaticAppUpdateFrequency?.isEnabled = enabled
                AutomaticAppUpdateScheduler.onSettingChanged(
                    ctx,
                    enabled,
                    automaticAppUpdateFrequency?.value?.toLongOrNull() ?: 0L,
                    checkNowForStartup = false
                )
                true
            }
            automaticAppUpdateFrequency?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = automaticAppUpdates?.isChecked == true
                val interval = (newValue as? String)?.toLongOrNull() ?: 0L
                AutomaticAppUpdateScheduler.onSettingChanged(
                    ctx,
                    enabled,
                    interval,
                    checkNowForStartup = false
                )
                true
            }
            findPreference<ListPreference>("automatic_cover_refresh_interval")?.setOnPreferenceChangeListener { _, newValue ->
                (newValue as? String)?.toLongOrNull() != null
            }
            findPreference<ListPreference>("global_search_concurrency")?.setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as? String)?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
                if (value !in 1..5) return@setOnPreferenceChangeListener false
                lifecycleScope.launch { App.instance.searchConcurrency.setLimit(value) }
                true
            }

            findPreference<SwitchPreferenceCompat>("show_nsfw_ext")?.setOnPreferenceChangeListener { pref, newValue ->
                if (newValue == true) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Confirm your age")
                        .setMessage("This unlocks sources and extensions with adult (18+) content, including MangaDex's erotica and pornographic ratings. Continue only if you are 18 or older.")
                        .setPositiveButton("I'm 18 or older") { _, _ -> (pref as SwitchPreferenceCompat).isChecked = true }
                        .setNegativeButton("Cancel", null)
                        .show()
                    false
                } else {
                    true
                }
            }
            findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                ctx.toast("Cache cleared")
                true
            }
            findPreference<Preference>("clear_cookies")?.setOnPreferenceClickListener {
                CookieManager.getInstance().removeAllCookies(null)
                ctx.toast("Cookies cleared")
                true
            }
        }

        private fun hasDeviceAuthentication(context: android.content.Context): Boolean {
            val hasSecureScreenLock = (context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as KeyguardManager)
                .isDeviceSecure
            val hasBiometrics = BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            return hasSecureScreenLock || hasBiometrics
        }
    }
}
