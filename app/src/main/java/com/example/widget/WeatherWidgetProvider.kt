package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.DailyForecastItem
import com.example.data.PreferencesHelper
import com.example.data.UnitFormatter
import com.example.data.WeatherCodeMapper
import com.example.data.WeatherDatabase
import com.example.data.deriveDailyWeatherCode
import com.example.worker.WeatherSyncWorker
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

private const val ACTION_REFRESH_WIDGET = "com.example.widget.action.REFRESH"

abstract class RefreshableWeatherWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH_WIDGET) {
            WeatherSyncWorker.refreshNow(context)
            return
        }
        super.onReceive(context, intent)
    }
}

open class WeatherWidgetProvider : RefreshableWeatherWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { widgetId ->
                    updateCompactWidget(context, appWidgetManager, widgetId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        fun triggerUpdate(context: Context) {
            sendUpdateBroadcast(context, WeatherWidgetProvider::class.java)
            sendUpdateBroadcast(context, WeatherWidgetLargeProvider::class.java)
        }

        private fun sendUpdateBroadcast(
            context: Context,
            providerClass: Class<out AppWidgetProvider>
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, providerClass))
            if (ids.isEmpty()) return
            val intent = Intent(context, providerClass).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}

class WeatherWidgetLargeProvider : RefreshableWeatherWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgetsAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidgetsAsync(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun updateWidgetsAsync(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { widgetId ->
                    updateLargeWidget(context, appWidgetManager, widgetId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private suspend fun updateCompactWidget(
    context: Context,
    manager: AppWidgetManager,
    widgetId: Int
) {
    val views = RemoteViews(context.packageName, R.layout.widget_compact)
    bindBaseActions(
        views = views,
        context = context,
        widgetId = widgetId,
        providerClass = WeatherWidgetProvider::class.java
    )

    try {
        val db = WeatherDatabase.getDatabase(context)
        val activeId = PreferencesHelper.getActiveLocationId(context)
        if (activeId != -1L) {
            val cache = db.weatherCacheDao.getWeatherCacheByLocation(activeId)
            val location = db.savedLocationDao.getLocationById(activeId)
            if (cache != null && location != null) {
                val isCelsius = PreferencesHelper.getTempUnit(context) == "C"
                val visual = widgetVisual(cache.weatherCode)
                val temp = UnitFormatter.tempFromC(cache.currentTemp, isCelsius)

                views.setInt(R.id.widget_root, "setBackgroundResource", visual.backgroundRes)
                views.setImageViewResource(R.id.widget_icon, visual.iconRes)
                views.setTextViewText(R.id.widget_temp, "${temp.toInt()}°")
                views.setTextViewText(R.id.widget_location, locationShortLabel(location.name))
                views.setTextViewText(
                    R.id.widget_desc,
                    WeatherCodeMapper.getWeatherDescription(cache.weatherCode)
                )
                views.setTextViewText(R.id.widget_updated, formatRelativeUpdate(cache.lastUpdatedMillis))
            } else {
                views.setTextViewText(R.id.widget_desc, "Tap refresh to load weather")
            }

            bindHazardBadge(
                views = views,
                context = context,
                widgetId = widgetId,
                hazard = findWidgetHazard(db, activeId),
                compact = true
            )
        }
    } catch (_: Exception) {
        views.setTextViewText(R.id.widget_desc, "Weather update unavailable")
        views.setTextViewText(R.id.widget_updated, "Tap refresh to retry")
    }
    manager.updateAppWidget(widgetId, views)
}

private suspend fun updateLargeWidget(
    context: Context,
    manager: AppWidgetManager,
    widgetId: Int
) {
    val views = RemoteViews(context.packageName, R.layout.widget_large)
    bindBaseActions(
        views = views,
        context = context,
        widgetId = widgetId,
        providerClass = WeatherWidgetLargeProvider::class.java
    )

    var forecastDayCount = 0
    try {
        val db = WeatherDatabase.getDatabase(context)
        val activeId = PreferencesHelper.getActiveLocationId(context)
        if (activeId != -1L) {
            val cache = db.weatherCacheDao.getWeatherCacheByLocation(activeId)
            val location = db.savedLocationDao.getLocationById(activeId)
            if (cache != null && location != null) {
                val isCelsius = PreferencesHelper.getTempUnit(context) == "C"
                val windUnit = PreferencesHelper.getWindSpeedUnit(context)
                val visual = widgetVisual(cache.weatherCode)
                val temp = UnitFormatter.tempFromC(cache.currentTemp, isCelsius)
                val apparent = UnitFormatter.tempFromC(cache.feelsLike, isCelsius)

                views.setInt(R.id.widget_root, "setBackgroundResource", visual.backgroundRes)
                views.setImageViewResource(R.id.widget_icon, visual.iconRes)
                views.setTextViewText(R.id.widget_temp, "${temp.toInt()}°")
                views.setTextViewText(
                    R.id.widget_cond,
                    WeatherCodeMapper.getWeatherDescription(cache.weatherCode)
                )
                views.setTextViewText(R.id.widget_feels_like, "Feels ${apparent.toInt()}°")
                views.setTextViewText(
                    R.id.widget_wind,
                    "Wind ${UnitFormatter.windFromMs(cache.windSpeed, windUnit)}"
                )
                views.setTextViewText(R.id.widget_humidity, "Humidity ${cache.humidity.toInt()}%")
                views.setTextViewText(R.id.widget_location, widgetLocationLabel(location))
                views.setTextViewText(R.id.widget_updated, formatRelativeUpdate(cache.lastUpdatedMillis))

                val daily = parseDailyForecast(cache.dailyJson)
                daily.firstOrNull()?.values?.let { today ->
                    val high = today.temperatureMax?.let { UnitFormatter.tempFromC(it, isCelsius) }
                    val low = today.temperatureMin?.let { UnitFormatter.tempFromC(it, isCelsius) }
                    views.setTextViewText(
                        R.id.widget_hilo,
                        "H:${high?.toInt() ?: "--"}°  L:${low?.toInt() ?: "--"}°"
                    )
                }

                val forecastDays = daily.drop(1).take(3)
                forecastDayCount = forecastDays.size
                val rowIds = listOf(
                    ForecastRowIds(
                        R.id.widget_day1_name,
                        R.id.widget_day1_cond,
                        R.id.widget_day1_hilo
                    ),
                    ForecastRowIds(
                        R.id.widget_day2_name,
                        R.id.widget_day2_cond,
                        R.id.widget_day2_hilo
                    ),
                    ForecastRowIds(
                        R.id.widget_day3_name,
                        R.id.widget_day3_cond,
                        R.id.widget_day3_hilo
                    )
                )
                forecastDays.forEachIndexed { index, day ->
                    val ids = rowIds[index]
                    val high = day.values.temperatureMax?.let {
                        UnitFormatter.tempFromC(it, isCelsius)
                    }
                    val low = day.values.temperatureMin?.let {
                        UnitFormatter.tempFromC(it, isCelsius)
                    }
                    views.setTextViewText(ids.name, parseDayName(day.time))
                    views.setTextViewText(
                        ids.condition,
                        WeatherCodeMapper.getWeatherDescription(deriveDailyWeatherCode(day.values))
                    )
                    views.setTextViewText(
                        ids.highLow,
                        "${high?.toInt() ?: "--"}° / ${low?.toInt() ?: "--"}°"
                    )
                }
            } else {
                views.setTextViewText(R.id.widget_cond, "Tap refresh to load weather")
            }

            bindHazardBadge(
                views = views,
                context = context,
                widgetId = widgetId,
                hazard = findWidgetHazard(db, activeId),
                compact = false
            )
        }
    } catch (_: Exception) {
        views.setTextViewText(R.id.widget_cond, "Weather update unavailable")
        views.setTextViewText(R.id.widget_updated, "Tap refresh to retry")
    }

    applyLargeWidgetDensity(manager, widgetId, views, forecastDayCount)
    manager.updateAppWidget(widgetId, views)
}

private fun bindBaseActions(
    views: RemoteViews,
    context: Context,
    widgetId: Int,
    providerClass: Class<out AppWidgetProvider>
) {
    views.setOnClickPendingIntent(
        R.id.widget_root,
        activityPendingIntent(context, widgetId * 10)
    )
    val refreshIntent = Intent(context, providerClass).apply {
        action = ACTION_REFRESH_WIDGET
    }
    views.setOnClickPendingIntent(
        R.id.widget_refresh,
        PendingIntent.getBroadcast(
            context,
            widgetId,
            refreshIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )
}

private fun bindHazardBadge(
    views: RemoteViews,
    context: Context,
    widgetId: Int,
    hazard: WidgetHazard?,
    compact: Boolean
) {
    if (hazard == null) {
        views.setViewVisibility(R.id.widget_storm_badge, View.GONE)
        return
    }

    views.setViewVisibility(R.id.widget_storm_badge, View.VISIBLE)
    views.setTextViewText(
        R.id.widget_storm_badge,
        if (compact) hazard.shortLabel else hazard.label
    )
    views.setContentDescription(R.id.widget_storm_badge, hazard.accessibilityLabel)
    views.setOnClickPendingIntent(
        R.id.widget_storm_badge,
        activityPendingIntent(
            context = context,
            requestCode = widgetId * 10 + 2,
            alertId = hazard.alertId,
            destination = hazard.destination
        )
    )
}

private suspend fun findWidgetHazard(
    db: WeatherDatabase,
    activeLocationId: Long
): WidgetHazard? {
    val alerts = db.activeAlertDao.getAllAlerts()
        .filter { alert ->
            !alert.dismissed && (
                alert.id.startsWith("storm_prox_") ||
                    alert.id.startsWith("nws_${activeLocationId}_")
                )
        }
    val highestAlert = alerts.maxByOrNull { severityWeight(it.severity) }
    if (highestAlert != null) {
        val extraCount = alerts.size - 1
        val suffix = if (extraCount > 0) " +$extraCount" else ""
        return WidgetHazard(
            label = "⚠ ${highestAlert.title}$suffix",
            shortLabel = alerts.size.coerceAtMost(9).toString() + if (alerts.size > 9) "+" else "",
            accessibilityLabel = "${alerts.size} active weather alert${if (alerts.size == 1) "" else "s"}",
            alertId = highestAlert.id
        )
    }

    val storm = db.stormCacheDao.getAllStorms()
        .maxWithOrNull(compareBy({ it.category }, { it.windSpeedKt }))
    return storm?.let {
        WidgetHazard(
            label = "● ${it.type} ${it.name}",
            shortLabel = "●",
            accessibilityLabel = "Active tropical system: ${it.type} ${it.name}",
            destination = MainActivity.DESTINATION_TROPICS
        )
    }
}

private fun activityPendingIntent(
    context: Context,
    requestCode: Int,
    alertId: String? = null,
    destination: String? = null
): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        alertId?.let { putExtra(MainActivity.EXTRA_ALERT_ID, it) }
        destination?.let { putExtra(MainActivity.EXTRA_DESTINATION, it) }
    }
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

private fun applyLargeWidgetDensity(
    manager: AppWidgetManager,
    widgetId: Int,
    views: RemoteViews,
    availableForecastDays: Int
) {
    val options = manager.getAppWidgetOptions(widgetId)
    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 190)
        .takeIf { it > 0 } ?: 190
    val showMetrics = minHeight >= 120
    val showDay1 = minHeight >= 145 && availableForecastDays >= 1
    val showDay2 = minHeight >= 165 && availableForecastDays >= 2
    val showDay3 = minHeight >= 185 && availableForecastDays >= 3

    views.setViewVisibility(R.id.widget_metrics_divider, visibility(showMetrics))
    views.setViewVisibility(R.id.widget_metrics_row, visibility(showMetrics))
    views.setViewVisibility(R.id.widget_forecast_divider, visibility(showDay1))
    views.setViewVisibility(R.id.widget_day1_row, visibility(showDay1))
    views.setViewVisibility(R.id.widget_day2_row, visibility(showDay2))
    views.setViewVisibility(R.id.widget_day3_row, visibility(showDay3))
}

private fun visibility(visible: Boolean): Int = if (visible) View.VISIBLE else View.GONE

private fun parseDailyForecast(json: String): List<DailyForecastItem> {
    val type = Types.newParameterizedType(List::class.java, DailyForecastItem::class.java)
    return try {
        Moshi.Builder().build().adapter<List<DailyForecastItem>>(type).fromJson(json).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun widgetVisual(weatherCode: Int): WidgetVisual = when (weatherCode) {
    1000, 1100 -> WidgetVisual(R.drawable.ic_widget_sunny, R.drawable.widget_bg_clear)
    1001, 1101, 1102, 2000, 2100 -> {
        WidgetVisual(R.drawable.ic_widget_cloud, R.drawable.widget_bg_cloud)
    }
    4000, 4001, 4200, 4201, 6000, 6001, 6200, 6201 -> {
        WidgetVisual(R.drawable.ic_widget_rain, R.drawable.widget_bg_rain)
    }
    5000, 5001, 5100, 5101, 7000, 7101, 7102 -> {
        WidgetVisual(R.drawable.ic_widget_snow, R.drawable.widget_bg_snow)
    }
    8000 -> WidgetVisual(R.drawable.ic_widget_storm, R.drawable.widget_bg_storm)
    else -> WidgetVisual(R.drawable.ic_widget_cloud, R.drawable.widget_bg_cloud)
}

private fun widgetLocationLabel(location: com.example.data.SavedLocation): String {
    return if (location.isGPS) {
        val latHemisphere = if (location.latitude >= 0) "N" else "S"
        val lonHemisphere = if (location.longitude >= 0) "E" else "W"
        "${"%.1f".format(abs(location.latitude))}°$latHemisphere  ${"%.1f".format(abs(location.longitude))}°$lonHemisphere"
    } else {
        locationShortLabel(location.name)
    }
}

private fun locationShortLabel(name: String): String =
    name.substringBefore(',').trim().ifBlank { name }

private fun formatRelativeUpdate(millis: Long): String {
    val ageMinutes = ((System.currentTimeMillis() - millis).coerceAtLeast(0L) / 60_000L)
    return when {
        ageMinutes < 1 -> "Updated now"
        ageMinutes < 60 -> "Updated ${ageMinutes}m ago"
        ageMinutes < 24 * 60 -> "Updated ${ageMinutes / 60}h ago"
        else -> "Updated " + SimpleDateFormat("MMM d · h:mm a", Locale.getDefault())
            .format(Date(millis))
    }
}

private fun parseDayName(isoTime: String): String = try {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val date = formatter.parse(isoTime) ?: return "—"
    SimpleDateFormat("EEE", Locale.getDefault()).format(date)
} catch (_: Exception) {
    "—"
}

private fun severityWeight(severity: String): Int = when (severity.lowercase()) {
    "extreme" -> 4
    "severe" -> 3
    "moderate" -> 2
    "minor" -> 1
    else -> 0
}

private data class WidgetVisual(val iconRes: Int, val backgroundRes: Int)

private data class ForecastRowIds(val name: Int, val condition: Int, val highLow: Int)

private data class WidgetHazard(
    val label: String,
    val shortLabel: String,
    val accessibilityLabel: String,
    val alertId: String? = null,
    val destination: String? = null
)
