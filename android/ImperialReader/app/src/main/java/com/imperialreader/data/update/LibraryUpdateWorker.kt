package com.imperialreader.data.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.imperialreader.util.app
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class LibraryUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.app.prefs
        val runOnAppStart = inputData.getBoolean(KEY_RUN_ON_APP_START, false)
        if (!prefs.refreshOnStart || (prefs.libraryUpdateIntervalMinutes <= 0L && !runOnAppStart)) {
            return Result.success()
        }
        return try {
            setForeground(LibraryRefreshNotification.foregroundInfo(applicationContext, 0, 0))
            LibraryRefreshNotification.show(applicationContext, 0, 0)
            val failures = AtomicInteger()
            applicationContext.app.repo.updateLibrary(
                onProgress = { done, total -> LibraryRefreshNotification.show(applicationContext, done, total) },
                onFailure = { _, _ -> failures.incrementAndGet() }
            )
            if (failures.get() > 0) {
                throw IOException("Could not update ${failures.get()} library entries")
            }
            prefs.libraryUpdatedAt = System.currentTimeMillis()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Automatic library update failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            LibraryRefreshNotification.clear(applicationContext)
        }
    }

    companion object {
        private const val TAG = "LibraryUpdateWorker"
        private const val MAX_ATTEMPTS = 3
        const val KEY_RUN_ON_APP_START = "run_on_app_start"
    }
}

object LibraryUpdateScheduler {
    private const val UNIQUE_NAME = "imperial_reader_automatic_library_update"
    private const val APP_START_UNIQUE_NAME = "imperial_reader_library_update_on_app_start"

    fun onAppStart(context: Context) {
        val prefs = context.applicationContext.app.prefs
        onSettingChanged(context, prefs.refreshOnStart, prefs.libraryUpdateIntervalMinutes)
    }

    fun onSettingChanged(context: Context, enabled: Boolean, intervalMinutes: Long) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled || intervalMinutes <= 0L) {
            workManager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun enqueueOnAppStart(context: Context) {
        val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
            .setInputData(Data.Builder().putBoolean(LibraryUpdateWorker.KEY_RUN_ON_APP_START, true).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(APP_START_UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
