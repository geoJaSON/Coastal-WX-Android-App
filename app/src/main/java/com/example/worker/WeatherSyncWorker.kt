package com.example.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.PreferencesHelper
import com.example.data.WeatherDatabase
import com.example.data.WeatherRepository
import com.example.widget.WeatherWidgetProvider
import java.util.concurrent.TimeUnit

/**
 * Keeps the weather cache, alerts, storm list, and home-screen widgets fresh while the
 * app process is dead. Mirrors the foreground refresh in MainViewModel.refreshAllData,
 * minus UI-only work (tides, geocoding).
 */
class WeatherSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = WeatherRepository.getInstance(applicationContext)
        repository.createAlertNotificationChannel()

        val db = WeatherDatabase.getDatabase(applicationContext)
        val activeId = PreferencesHelper.getActiveLocationId(applicationContext)
        val location = if (activeId != -1L) db.savedLocationDao.getLocationById(activeId) else null

        if (location != null) {
            repository.refreshForecast(location)
            repository.syncNWSAlerts(location)
        }
        repository.syncStorms()
        WeatherWidgetProvider.triggerUpdate(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "weather_sync"
        private const val MANUAL_WORK_NAME = "weather_sync_manual"

        /**
         * Idempotent — safe to call on every app start and whenever the refresh-interval
         * preference changes. WorkManager enforces a 15-minute floor on periodic work,
         * matching the slider's minimum.
         */
        fun ensureScheduled(context: Context) {
            val intervalMins = PreferencesHelper.getRefreshIntervalMinutes(context)
                .coerceAtLeast(15)
                .toLong()
            val request = PeriodicWorkRequestBuilder<WeatherSyncWorker>(intervalMins, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WeatherSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
