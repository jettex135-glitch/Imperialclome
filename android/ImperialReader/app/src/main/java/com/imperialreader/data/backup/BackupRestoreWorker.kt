package com.imperialreader.data.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.imperialreader.R
import kotlinx.coroutines.CancellationException

data class BackupRestoreProgress(
    val stage: String,
    val completed: Int? = null,
    val total: Int? = null
) {
    val percentage: Int?
        get() = if (completed != null && total != null && total > 0) {
            (completed.toLong().coerceIn(0L, total.toLong()) * 100L / total).toInt()
        } else {
            null
        }
}

class BackupRestoreWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse)
            ?: return Result.failure(Data.Builder().putString(KEY_ERROR, "Backup location is missing").build())
        var lastPercentage = -1
        var lastStage = ""
        var lastNotificationUpdateAt = 0L
        return try {
            setForeground(BackupRestoreNotification.foregroundInfo(applicationContext, BackupRestoreProgress("Starting restoration")))
            val restored = BackupManager(applicationContext).restore(uri) { progress ->
                val percentage = progress.percentage
                val now = SystemClock.elapsedRealtime()
                val stageChanged = progress.stage != lastStage && now - lastNotificationUpdateAt >= NOTIFICATION_UPDATE_INTERVAL_MS
                if (percentage == null || percentage != lastPercentage || stageChanged) {
                    if (percentage != null) lastPercentage = percentage
                    lastStage = progress.stage
                    lastNotificationUpdateAt = now
                    setProgress(
                        Data.Builder()
                            .putInt(KEY_COMPLETED, progress.completed ?: 0)
                            .putInt(KEY_TOTAL, progress.total ?: 0)
                            .putString(KEY_STAGE, progress.stage)
                            .build()
                    )
                    setForeground(BackupRestoreNotification.foregroundInfo(applicationContext, progress))
                }
            }
            BackupRestoreNotification.showComplete(applicationContext, restored)
            Result.success(Data.Builder().putInt(KEY_RESTORED, restored).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupRestoreNotification.showFailure(applicationContext, e.message)
            Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR, e.message ?: "Could not restore the selected backup")
                    .build()
            )
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "imperial_reader_backup_restore"
        private const val KEY_URI = "backup_uri"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_TOTAL = "total"
        private const val KEY_STAGE = "stage"
        private const val KEY_RESTORED = "restored"
        private const val KEY_ERROR = "error"
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L
        private const val FALLBACK_PREFS = "backup_restore_status"
        private const val FALLBACK_RESULT = "last_result"

        fun enqueue(context: Context, uri: Uri) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val request = OneTimeWorkRequestBuilder<BackupRestoreWorker>()
                .setInputData(Data.Builder().putString(KEY_URI, uri.toString()).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun consumeFallbackStatus(context: Context): String? {
            val preferences = context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
            val status = preferences.getString(FALLBACK_RESULT, null) ?: return null
            preferences.edit().remove(FALLBACK_RESULT).apply()
            return status
        }
    }
}

private object BackupRestoreNotification {
    private const val CHANNEL_ID = "backup_restoration"
    private const val PROGRESS_NOTIFICATION_ID = 1005
    private const val RESULT_NOTIFICATION_ID = 1006

    fun foregroundInfo(context: Context, progress: BackupRestoreProgress): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_updates)
            .setContentTitle("Restoring Backup")
            .setContentText(progressText(progress))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(progress.total ?: 0, progress.completed ?: 0, progress.percentage == null)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                PROGRESS_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    fun showComplete(context: Context, restored: Int) {
        notifyResult(
            context,
            "Backup restored",
            "Restored $restored library ${if (restored == 1) "entry" else "entries"}"
        )
    }

    fun showFailure(context: Context, message: String?) {
        notifyResult(
            context,
            "Backup restore failed",
            message?.takeIf(String::isNotBlank)?.take(160) ?: "Could not restore the selected backup"
        )
    }

    private fun notifyResult(context: Context, title: String, message: String) {
        ensureChannel(context)
        val notificationsAllowed = manager(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                manager(context).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)
        if (!notificationsAllowed) {
            context.getSharedPreferences("backup_restore_status", Context.MODE_PRIVATE)
                .edit()
                .putString("last_result", "$title: $message")
                .apply()
            return
        }
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_updates)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        manager(context).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun progressText(progress: BackupRestoreProgress): String =
        progress.percentage?.let { "$it% · ${progress.stage}" } ?: progress.stage

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager(context).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Backup restoration", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
