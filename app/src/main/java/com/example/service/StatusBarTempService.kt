package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.PreferencesHelper
import com.example.data.UnitFormatter
import com.example.data.WeatherCodeMapper
import com.example.data.WeatherDatabase
import kotlinx.coroutines.*

class StatusBarTempService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var observationJob: Job? = null

    companion object {
        const val CHANNEL_ID = "status_bar_temp_channel"
        const val NOTIFICATION_ID = 4501
        
        fun startService(context: Context) {
            val intent = Intent(context, StatusBarTempService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, StatusBarTempService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildTempNotification("Coastal WX: Loading...")
        startForeground(NOTIFICATION_ID, initialNotification)
        
        startObservingCache()
        return START_STICKY
    }

    private fun startObservingCache() {
        observationJob?.cancel()
        observationJob = serviceScope.launch {
            while (isActive) {
                try {
                    val db = WeatherDatabase.getDatabase(applicationContext)
                    val activeId = PreferencesHelper.getActiveLocationId(applicationContext)
                    if (activeId != -1L) {
                        val cache = db.weatherCacheDao.getWeatherCacheByLocation(activeId)
                        if (cache != null) {
                            val tempCValue = cache.currentTemp
                            val isCelsius = PreferencesHelper.getTempUnit(applicationContext) == "C"
                            val currentTempFinal = UnitFormatter.tempFromC(tempCValue, isCelsius)
                            val unitLabel = if (isCelsius) "°C" else "°F"
                            val weatherText = "${currentTempFinal.toInt()}$unitLabel • " +
                                WeatherCodeMapper.getWeatherDescription(cache.weatherCode)
                            
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(NOTIFICATION_ID, buildTempNotification(weatherText))
                        }
                    }
                } catch (e: Exception) {
                    // Fail silently inside services
                }
                delay(120000) // Refresh status notification text every 2 mins
            }
        }
    }

    private fun buildTempNotification(contentText: String): Notification {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Coastal WX Current Conditions")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_thermostat)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Status Bar Temperature",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Unobtrusive active temperature readout in notification tray"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        observationJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
