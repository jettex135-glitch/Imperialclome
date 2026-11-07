package com.imperialreader.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.imperialreader.R
import com.imperialreader.util.app
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit

class AutomaticAppUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.app.prefs
        if (!prefs.automaticAppUpdates) return Result.success()
        return try {
            val apk = AppUpdateManager(applicationContext).checkAndDownloadUpdate()
            if (apk != null) showUpdateNotification(applicationContext, apk)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Automatic app update check failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun showUpdateNotification(context: Context, apk: File) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val installPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Imperial Reader update available")
            .setContentText("Tap to install the latest APK from GitHub")
            .setContentIntent(installPendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "AutomaticAppUpdate"
        private const val MAX_ATTEMPTS = 3
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 1004
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

object AutomaticAppUpdateScheduler {
    private const val PERIODIC_NAME = "imperial_reader_automatic_app_update"
    private const val START_NAME = "imperial_reader_app_update_on_start"

    fun onAppStart(context: Context) {
        val prefs = context.applicationContext.app.prefs
        onSettingChanged(
            context,
            prefs.automaticAppUpdates,
            prefs.automaticAppUpdateIntervalMinutes,
            checkNowForStartup = true,
            keepExistingPeriodicWork = true
        )
    }

    fun onSettingChanged(
        context: Context,
        enabled: Boolean,
        intervalMinutes: Long,
        checkNowForStartup: Boolean = false,
        keepExistingPeriodicWork: Boolean = false
    ) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_NAME)
            workManager.cancelUniqueWork(START_NAME)
            return
        }
        if (intervalMinutes > 0L) {
            workManager.cancelUniqueWork(START_NAME)
            val request = PeriodicWorkRequestBuilder<AutomaticAppUpdateWorker>(
                intervalMinutes,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints())
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                if (keepExistingPeriodicWork) ExistingPeriodicWorkPolicy.KEEP
                else ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } else {
            workManager.cancelUniqueWork(PERIODIC_NAME)
            if (checkNowForStartup) {
                val request = OneTimeWorkRequestBuilder<AutomaticAppUpdateWorker>()
                    .setConstraints(networkConstraints())
                    .build()
                workManager.enqueueUniqueWork(START_NAME, ExistingWorkPolicy.KEEP, request)
            }
        }
    }

    private fun networkConstraints() =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}
