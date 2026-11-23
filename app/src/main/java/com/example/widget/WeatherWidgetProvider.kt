package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.DailyForecastItem
import com.example.data.HourlyForecastItem
import com.example.data.PreferencesHelper
import com.example.data.SavedLocation
import com.example.data.UnitFormatter
import com.example.data.WeatherCodeMapper
import com.example.data.WeatherDatabase
import com.example.data.deriveDailyWeatherCode
import com.example.worker.WeatherSyncWorker
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ACTION_REFRESH_WIDGET = "com.example.widget.action.REFRESH"

abstract class RefreshableWeatherWidgetProvider : AppWidgetProvider() {

    /** Layout to repaint with a "Refreshing…" note the moment the refresh button is tapped. */
    protected abstract val layoutRes: Int

    /**
     * View to write that note into, or null when the layout shows no timestamp - the compact
     * widget trades its "updated" line for forecast width, and addressing a view that the
     * layout does not contain is not something RemoteViews handles consistently.
     */
    protected open val refreshFeedbackViewId: Int? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH_WIDGET) {
            acknowledgeRefresh(context, intent)
            WeatherSyncWorker.refreshNow(context)
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * The sync worker takes a while to come back, and until it does the widget looks like the
     * tap did nothing. Repaint the timestamp line immediately so the button feels connected.
     */
    private fun acknowledgeRefresh(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val feedbackViewId = refreshFeedbackViewId ?: return
        try {
            val views = RemoteViews(context.packageName, layoutRes)
            views.setTextViewText(
                feedbackViewId,
                context.getString(R.string.widget_refreshing)
            )
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(widgetId, views)
        } catch (_: Exception) {
            // A missing widget or a host that rejects partial updates is not worth failing over.
        }
    }
}

open class WeatherWidgetProvider : RefreshableWeatherWidgetProvider() {

    override val layoutRes: Int = R.layout.widget_compact

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        renderAsync(context, appWidgetManager, appWidgetIds, ::renderCompactWidget)
    }

    /**
     * Without this the compact widget never re-lays out after a resize, so its width-gated
     * forecast columns would be stuck at whatever the first render decided.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        renderAsync(context, appWidgetManager, intArrayOf(appWidgetId), ::renderCompactWidget)
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

    override val layoutRes: Int = R.layout.widget_large

    override val refreshFeedbackViewId: Int = R.id.widget_updated

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        renderAsync(context, appWidgetManager, appWidgetIds, ::renderLargeWidget)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        renderAsync(context, appWidgetManager, intArrayOf(appWidgetId), ::renderLargeWidget)
    }
}

private fun AppWidgetProvider.renderAsync(
    context: Context,
    manager: AppWidgetManager,
    widgetIds: IntArray,
    render: (Context, AppWidgetManager, Int, WidgetState) -> Unit
) {
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val state = loadWidgetState(context)
            widgetIds.forEach { widgetId -> render(context, manager, widgetId, state) }
        } finally {
            pendingResult.finish()
        }
    }
}

// ── Data loading ────────────────────────────────────────────────────────────

/**
 * Every branch carries text for every slot. Binding only some views leaves the rest showing
 * whatever the last successful render put there, because the host reapplies onto a recycled
 * view tree.
 */
internal sealed interface WidgetState {
    data class Ready(val data: WidgetData) : WidgetState
    /** [shortMessage] is for the compact widget, whose condition line is capped at 112dp. */
    data class Empty(
        val message: String,
        val shortMessage: String,
        val hazard: WidgetHazard?
    ) : WidgetState
}

internal data class WidgetData(
    val temperature: String,
    val condition: String,
    val iconRes: Int,
    val backgroundRes: Int,
    val locationLabel: String,
    val updatedLabel: String,
    val highLow: String,
    val feelsLike: String,
    val humidity: String,
    val wind: String,
    val uvIndex: String,
    val days: List<WidgetDay>,
    val hours: List<WidgetHour>,
    val hazard: WidgetHazard?
)

internal data class WidgetDay(
    val name: String,
    val iconRes: Int,
    val description: String,
    val precipitation: String,
    val highLow: String,
    val high: String
)

internal data class WidgetHour(
    val time: String,
    val iconRes: Int,
    val description: String,
    val temperature: String
)

internal suspend fun loadWidgetState(context: Context): WidgetState {
    return try {
        val db = WeatherDatabase.getDatabase(context)
        val activeId = PreferencesHelper.getActiveLocationId(context)
        if (activeId == -1L) {
            return WidgetState.Empty(
                context.getString(R.string.widget_no_location),
                context.getString(R.string.widget_no_location_short),
                null
            )
        }

        val hazard = findWidgetHazard(db, activeId)
        val cache = db.weatherCacheDao.getWeatherCacheByLocation(activeId)
        val location = db.savedLocationDao.getLocationById(activeId)
        if (cache == null || location == null) {
            return WidgetState.Empty(
                context.getString(R.string.widget_loading),
                context.getString(R.string.widget_loading_short),
                hazard
            )
        }

        val isCelsius = PreferencesHelper.getTempUnit(context) == "C"
        val windUnit = PreferencesHelper.getWindSpeedUnit(context)
        val daily = parseDailyForecast(cache.dailyJson)
        val hourly = parseHourlyForecast(cache.hourlyJson)
        val daylight = DaylightWindows.from(daily)

        val today = daily.firstOrNull()?.values
        val visual = widgetVisual(cache.weatherCode, daylight.isNight(System.currentTimeMillis()))

        WidgetState.Ready(
            WidgetData(
                temperature = formatTemp(cache.currentTemp, isCelsius),
                condition = WeatherCodeMapper.getWeatherDescription(cache.weatherCode),
                iconRes = visual.iconRes,
                backgroundRes = visual.backgroundRes,
                locationLabel = widgetLocationLabel(location),
                updatedLabel = formatRelativeUpdate(cache.lastUpdatedMillis),
                highLow = "H:${today?.temperatureMax.toTempOrDash(isCelsius)}" +
                    "  L:${today?.temperatureMin.toTempOrDash(isCelsius)}",
                feelsLike = "Feels ${formatTemp(cache.feelsLike, isCelsius)}",
                humidity = "RH ${cache.humidity.roundToInt()}%",
                wind = "${cardinalDirection(cache.windDirection)} " +
                    UnitFormatter.windFromMs(cache.windSpeed, windUnit),
                uvIndex = "UV ${cache.uvIndex.roundToInt()}",
                // daily[0] is today, already summarised by the hero readout above.
                days = daily.drop(1).take(4).mapIndexed { index, day ->
                    val code = deriveDailyWeatherCode(day.values)
                    val probability = day.values.precipitationProbabilityAvg
                    WidgetDay(
                        name = dayName(day.time, index + 1),
                        // Daily buckets summarise a whole day, so always use the daytime icon.
                        iconRes = widgetVisual(code, isNight = false).iconRes,
                        description = WeatherCodeMapper.getWeatherDescription(code),
                        precipitation = formatPrecipitation(probability),
                        highLow = "${day.values.temperatureMax.toTempOrDash(isCelsius)}/" +
                            day.values.temperatureMin.toTempOrDash(isCelsius),
                        high = day.values.temperatureMax.toTempOrDash(isCelsius)
                    )
                },
                // hourly[0] is the current hour and is what the hero readout already shows.
                hours = hourly.drop(1).take(6).map { hour ->
                    val code = hour.values.weatherCode ?: cache.weatherCode
                    val millis = parseIsoMillis(hour.time)
                    WidgetHour(
                        time = hourLabel(hour.time),
                        iconRes = widgetVisual(
                            code,
                            isNight = millis != null && daylight.isNight(millis)
                        ).iconRes,
                        description = WeatherCodeMapper.getWeatherDescription(code),
                        temperature = hour.values.temperature.toTempOrDash(isCelsius)
                    )
                },
                hazard = hazard
            )
        )
    } catch (_: Exception) {
        WidgetState.Empty(
            context.getString(R.string.widget_unavailable),
            context.getString(R.string.widget_unavailable_short),
            null
        )
    }
}

// ── Compact widget ──────────────────────────────────────────────────────────

private fun renderCompactWidget(
    context: Context,
    manager: AppWidgetManager,
    widgetId: Int,
    state: WidgetState
) {
    val size = widgetSize(manager, widgetId, fallbackWidth = 200, fallbackHeight = 64)
    manager.updateAppWidget(
        widgetId,
        orientedViews(
            landscape = buildCompactViews(
                context, widgetId, state, size.landscapeWidth, size.landscapeHeight
            ),
            portrait = buildCompactViews(
                context, widgetId, state, size.portraitWidth, size.portraitHeight
            ),
            sameInBothOrientations = size.isSquareish
        )
    )
}

internal fun buildCompactViews(
    context: Context,
    widgetId: Int,
    state: WidgetState,
    widthDp: Int,
    heightDp: Int
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_compact)
    bindBaseActions(views, context, widgetId, WeatherWidgetProvider::class.java)

    // Measured budget: the strip gets whatever is left after the conditions block, the 21dp
    // rule and the refresh button, and each column needs ~26dp before the day name and the
    // temperature start colliding. The refresh button is dropped before the forecast is,
    // because tapping the widget already opens the app (which syncs).
    val showRefresh = widthDp >= 235
    val forecastColumns = when {
        widthDp >= 235 -> 3
        widthDp >= 190 -> 2
        else -> 0
    }
    val temperatureSp = when {
        widthDp >= 235 -> 32f
        widthDp >= 190 -> 28f
        else -> 26f
    }
    val showCondition = heightDp >= 66
    val showDayLabels = heightDp >= 62

    views.setTextViewTextSize(R.id.widget_temp, TypedValue.COMPLEX_UNIT_SP, temperatureSp)

    views.setViewVisibility(R.id.widget_desc, visibility(showCondition))
    views.setViewVisibility(R.id.widget_divider, visibility(forecastColumns > 0))

    val columns = listOf(
        CompactColumnIds(R.id.widget_f1_col, R.id.widget_f1_day, R.id.widget_f1_icon, R.id.widget_f1_temp),
        CompactColumnIds(R.id.widget_f2_col, R.id.widget_f2_day, R.id.widget_f2_icon, R.id.widget_f2_temp),
        CompactColumnIds(R.id.widget_f3_col, R.id.widget_f3_day, R.id.widget_f3_icon, R.id.widget_f3_temp)
    )

    when (state) {
        is WidgetState.Empty -> {
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_cloud)
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_cloud)
            views.setTextViewText(
                R.id.widget_temp,
                context.getString(R.string.widget_temperature_placeholder)
            )
            views.setTextViewText(R.id.widget_desc, state.shortMessage)
            columns.forEach {
                it.clear(views, context)
                views.setViewVisibility(it.column, View.GONE)
            }
            views.setViewVisibility(R.id.widget_divider, View.GONE)
            bindHazardBadge(views, context, widgetId, state.hazard, compact = true)
        }

        is WidgetState.Ready -> {
            val data = state.data
            views.setInt(R.id.widget_root, "setBackgroundResource", data.backgroundRes)
            views.setImageViewResource(R.id.widget_icon, data.iconRes)
            views.setContentDescription(R.id.widget_icon, data.condition)
            views.setTextViewText(R.id.widget_temp, data.temperature)
            views.setTextViewText(R.id.widget_desc, data.condition)

            columns.forEachIndexed { index, ids ->
                val day = data.days.getOrNull(index)
                if (day == null || index >= forecastColumns) {
                    ids.clear(views, context)
                    views.setViewVisibility(ids.column, View.GONE)
                } else {
                    views.setViewVisibility(ids.column, View.VISIBLE)
                    views.setViewVisibility(ids.day, visibility(showDayLabels))
                    views.setTextViewText(ids.day, day.name)
                    views.setImageViewResource(ids.icon, day.iconRes)
                    views.setContentDescription(ids.icon, day.description)
                    views.setTextViewText(ids.temperature, day.high)
                }
            }
            if (data.days.isEmpty()) {
                views.setViewVisibility(R.id.widget_divider, View.GONE)
            }
            bindHazardBadge(views, context, widgetId, data.hazard, compact = true)
        }
    }

    // The refresh button and the alert badge together need about 46dp of height; when the
    // widget is shorter than that the alert wins, because it is the more urgent of the two.
    val hazardVisible = (state as? WidgetState.Ready)?.data?.hazard != null ||
        (state as? WidgetState.Empty)?.hazard != null
    views.setViewVisibility(
        R.id.widget_refresh,
        visibility(showRefresh && !(hazardVisible && heightDp < 58))
    )
    return views
}

private data class CompactColumnIds(
    val column: Int,
    val day: Int,
    val icon: Int,
    val temperature: Int
) {
    fun clear(views: RemoteViews, context: Context) {
        views.setTextViewText(day, context.getString(R.string.widget_dash_placeholder))
        views.setImageViewResource(icon, R.drawable.ic_widget_cloud)
        views.setTextViewText(
            temperature,
            context.getString(R.string.widget_dash_temp_placeholder)
        )
    }
}

// ── Large widget ────────────────────────────────────────────────────────────

private fun renderLargeWidget(
    context: Context,
    manager: AppWidgetManager,
    widgetId: Int,
    state: WidgetState
) {
    val size = widgetSize(manager, widgetId, fallbackWidth = 300, fallbackHeight = 220)
    manager.updateAppWidget(
        widgetId,
        orientedViews(
            landscape = buildLargeViews(
                context, widgetId, state, size.landscapeWidth, size.landscapeHeight
            ),
            portrait = buildLargeViews(
                context, widgetId, state, size.portraitWidth, size.portraitHeight
            ),
            sameInBothOrientations = size.isSquareish
        )
    )
}

internal fun buildLargeViews(
    context: Context,
    widgetId: Int,
    state: WidgetState,
    widthDp: Int,
    heightDp: Int
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_large)
    bindBaseActions(views, context, widgetId, WeatherWidgetLargeProvider::class.java)

    val dayRows = listOf(
        LargeDayIds(R.id.widget_day1_row, 0, R.id.widget_day1_name, R.id.widget_day1_icon, R.id.widget_day1_precip, R.id.widget_day1_hilo),
        LargeDayIds(R.id.widget_day2_row, R.id.widget_day2_divider, R.id.widget_day2_name, R.id.widget_day2_icon, R.id.widget_day2_precip, R.id.widget_day2_hilo),
        LargeDayIds(R.id.widget_day3_row, R.id.widget_day3_divider, R.id.widget_day3_name, R.id.widget_day3_icon, R.id.widget_day3_precip, R.id.widget_day3_hilo),
        LargeDayIds(R.id.widget_day4_row, R.id.widget_day4_divider, R.id.widget_day4_name, R.id.widget_day4_icon, R.id.widget_day4_precip, R.id.widget_day4_hilo)
    )
    val hourColumns = listOf(
        LargeHourIds(R.id.widget_h1_col, R.id.widget_h1_time, R.id.widget_h1_icon, R.id.widget_h1_temp),
        LargeHourIds(R.id.widget_h2_col, R.id.widget_h2_time, R.id.widget_h2_icon, R.id.widget_h2_temp),
        LargeHourIds(R.id.widget_h3_col, R.id.widget_h3_time, R.id.widget_h3_icon, R.id.widget_h3_temp),
        LargeHourIds(R.id.widget_h4_col, R.id.widget_h4_time, R.id.widget_h4_icon, R.id.widget_h4_temp),
        LargeHourIds(R.id.widget_h5_col, R.id.widget_h5_time, R.id.widget_h5_icon, R.id.widget_h5_temp),
        LargeHourIds(R.id.widget_h6_col, R.id.widget_h6_time, R.id.widget_h6_icon, R.id.widget_h6_temp)
    )

    val data = (state as? WidgetState.Ready)?.data
    val availableDays = data?.days?.size ?: 0
    val availableHours = data?.hours?.size ?: 0

    // An alert pill is full-width and costs about 30dp. Without charging it to the budget the
    // weighted forecast rows compress below the height of their own 20dp icons and clip them.
    val hazardVisible = data?.hazard != null || (state as? WidgetState.Empty)?.hazard != null
    val budgetDp = heightDp - if (hazardVisible) 30 else 0

    // Height budget, measured against the layout's natural heights: hero + panel need about
    // 140dp, the metrics grid 32dp, the hourly strip 66dp.
    val maxDayRows = when {
        budgetDp >= 176 -> 4
        budgetDp >= 152 -> 3
        budgetDp >= 128 -> 2
        else -> 1
    }
    val showMetrics = budgetDp >= 164
    val showHourly = budgetDp >= 206 && availableHours > 0
    // At the smallest resize with an alert pill showing, the hero column no longer fits its
    // own text; shed the least essential lines rather than let the pill overlap them.
    val showCondition = budgetDp >= 118
    val showHighLow = budgetDp >= 104
    val hourColumnCount = when {
        !showHourly -> 0
        widthDp >= 300 -> 6
        widthDp >= 250 -> 5
        else -> 4
    }

    views.setTextViewTextSize(
        R.id.widget_temp,
        TypedValue.COMPLEX_UNIT_SP,
        when {
            widthDp >= 300 -> 44f
            widthDp >= 260 -> 38f
            else -> 32f
        }
    )

    views.setViewVisibility(R.id.widget_forecast_panel, visibility(availableDays > 0))
    views.setViewVisibility(R.id.widget_icon, visibility(widthDp >= 250))
    views.setViewVisibility(R.id.widget_cond, visibility(showCondition))
    views.setViewVisibility(R.id.widget_hilo, visibility(showHighLow))
    views.setViewVisibility(R.id.widget_metrics_row, visibility(showMetrics))
    views.setViewVisibility(R.id.widget_hourly, visibility(showHourly))
    views.setViewVisibility(R.id.widget_hourly_divider, visibility(showHourly))
    views.setTextViewText(
        R.id.widget_forecast_label,
        context.getString(
            if (maxDayRows >= 4) R.string.widget_forecast_label
            else R.string.widget_forecast_label_short
        )
    )

    when (state) {
        is WidgetState.Empty -> {
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_cloud)
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_cloud)
            views.setTextViewText(
                R.id.widget_temp,
                context.getString(R.string.widget_temperature_placeholder)
            )
            views.setTextViewText(R.id.widget_cond, state.message)
            views.setTextViewText(
                R.id.widget_location,
                context.getString(R.string.widget_open_placeholder)
            )
            views.setTextViewText(R.id.widget_updated, "")
            views.setTextViewText(
                R.id.widget_hilo,
                context.getString(R.string.widget_hilo_placeholder)
            )
            views.setTextViewText(
                R.id.widget_feels_like,
                context.getString(R.string.widget_feels_placeholder)
            )
            views.setTextViewText(
                R.id.widget_humidity,
                context.getString(R.string.widget_humidity_short_placeholder)
            )
            views.setTextViewText(
                R.id.widget_wind,
                context.getString(R.string.widget_wind_placeholder)
            )
            views.setTextViewText(
                R.id.widget_uv,
                context.getString(R.string.widget_uv_placeholder)
            )
            dayRows.forEach { it.clear(views, context) }
            hourColumns.forEach { it.clear(views, context) }
            bindHazardBadge(views, context, widgetId, state.hazard, compact = false)
        }

        is WidgetState.Ready -> {
            val ready = state.data
            views.setInt(R.id.widget_root, "setBackgroundResource", ready.backgroundRes)
            views.setImageViewResource(R.id.widget_icon, ready.iconRes)
            views.setContentDescription(R.id.widget_icon, ready.condition)
            views.setTextViewText(R.id.widget_temp, ready.temperature)
            views.setTextViewText(R.id.widget_cond, ready.condition)
            views.setTextViewText(R.id.widget_location, ready.locationLabel)
            views.setTextViewText(R.id.widget_updated, ready.updatedLabel)
            views.setTextViewText(R.id.widget_hilo, ready.highLow)
            views.setTextViewText(R.id.widget_feels_like, ready.feelsLike)
            views.setTextViewText(R.id.widget_humidity, ready.humidity)
            views.setTextViewText(R.id.widget_wind, ready.wind)
            views.setTextViewText(R.id.widget_uv, ready.uvIndex)

            dayRows.forEachIndexed { index, ids ->
                val day = ready.days.getOrNull(index)
                if (day == null) ids.clear(views, context) else ids.bind(views, day)
            }
            hourColumns.forEachIndexed { index, ids ->
                val hour = ready.hours.getOrNull(index)
                if (hour == null) ids.clear(views, context) else ids.bind(views, hour)
            }
            bindHazardBadge(views, context, widgetId, ready.hazard, compact = false)
        }
    }

    dayRows.forEachIndexed { index, ids ->
        val visible = index < maxDayRows && index < availableDays
        views.setViewVisibility(ids.row, visibility(visible))
        // The first row has no divider above it; 0 is the sentinel for that.
        if (ids.divider != 0) views.setViewVisibility(ids.divider, visibility(visible))
        // In a squeezed panel the precip chip is what pushes "84°/75°" past the edge, and
        // the temperatures are the more important half of the row.
        views.setViewVisibility(ids.precipitation, visibility(widthDp >= 260))
    }
    hourColumns.forEachIndexed { index, ids ->
        views.setViewVisibility(
            ids.column,
            visibility(index < hourColumnCount && index < availableHours)
        )
    }
    return views
}

private data class LargeDayIds(
    val row: Int,
    val divider: Int,
    val name: Int,
    val icon: Int,
    val precipitation: Int,
    val highLow: Int
) {
    fun bind(views: RemoteViews, day: WidgetDay) {
        views.setTextViewText(name, day.name)
        views.setImageViewResource(icon, day.iconRes)
        views.setContentDescription(icon, day.description)
        views.setTextViewText(precipitation, day.precipitation)
        views.setTextViewText(highLow, day.highLow)
    }

    fun clear(views: RemoteViews, context: Context) {
        views.setTextViewText(name, context.getString(R.string.widget_dash_placeholder))
        views.setImageViewResource(icon, R.drawable.ic_widget_cloud)
        views.setTextViewText(precipitation, "")
        views.setTextViewText(
            highLow,
            context.getString(R.string.widget_forecast_temp_placeholder)
        )
    }
}

private data class LargeHourIds(
    val column: Int,
    val time: Int,
    val icon: Int,
    val temperature: Int
) {
    fun bind(views: RemoteViews, hour: WidgetHour) {
        views.setTextViewText(time, hour.time)
        views.setImageViewResource(icon, hour.iconRes)
        views.setContentDescription(icon, hour.description)
        views.setTextViewText(temperature, hour.temperature)
    }

    fun clear(views: RemoteViews, context: Context) {
        views.setTextViewText(time, context.getString(R.string.widget_dash_placeholder))
        views.setImageViewResource(icon, R.drawable.ic_widget_cloud)
        views.setTextViewText(
            temperature,
            context.getString(R.string.widget_dash_temp_placeholder)
        )
    }
}

// ── Sizing ──────────────────────────────────────────────────────────────────

/**
 * The four OPTION_APPWIDGET_* keys are not "current size" — the host derives them from both
 * orientations at once: MIN_WIDTH/MAX_HEIGHT describe portrait, MAX_WIDTH/MIN_HEIGHT describe
 * landscape. Reading MIN_HEIGHT as "how tall am I" reports the landscape height even while
 * rendering in portrait, which is why the previous large-widget layout hid its metrics row at
 * its own default size and left the content floating in a tall empty box.
 */
private data class WidgetSize(
    val portraitWidth: Int,
    val portraitHeight: Int,
    val landscapeWidth: Int,
    val landscapeHeight: Int
) {
    val isSquareish: Boolean
        get() = portraitWidth == landscapeWidth && portraitHeight == landscapeHeight
}

private fun widgetSize(
    manager: AppWidgetManager,
    widgetId: Int,
    fallbackWidth: Int,
    fallbackHeight: Int
): WidgetSize {
    // Hosts return an empty Bundle until they have reported a size at least once.
    val options = try {
        manager.getAppWidgetOptions(widgetId)
    } catch (_: Exception) {
        Bundle.EMPTY
    }

    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
    val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)

    return WidgetSize(
        portraitWidth = firstPositive(minWidth, maxWidth, fallbackWidth),
        portraitHeight = firstPositive(maxHeight, minHeight, fallbackHeight),
        landscapeWidth = firstPositive(maxWidth, minWidth, fallbackWidth),
        landscapeHeight = firstPositive(minHeight, maxHeight, fallbackHeight)
    )
}

private fun firstPositive(vararg values: Int): Int = values.firstOrNull { it > 0 } ?: 0

/**
 * RemoteViews(landscape, portrait) — API 16 — is the only way to give the two orientations
 * different treatment before the API 31 SizeF map, since one RemoteViews cannot know which
 * orientation it is being drawn in.
 */
private fun orientedViews(
    landscape: RemoteViews,
    portrait: RemoteViews,
    sameInBothOrientations: Boolean
): RemoteViews = if (sameInBothOrientations) portrait else RemoteViews(landscape, portrait)

// ── Shared binding ──────────────────────────────────────────────────────────

private fun bindBaseActions(
    views: RemoteViews,
    context: Context,
    widgetId: Int,
    providerClass: Class<out AppWidgetProvider>
) {
    views.setOnClickPendingIntent(
        R.id.widget_root,
        activityPendingIntent(context, widgetId * 100)
    )
    val refreshIntent = Intent(context, providerClass).apply {
        action = ACTION_REFRESH_WIDGET
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        // Extras alone do not make two Intents distinct for PendingIntent matching, so the
        // data URI is what keeps per-widget refresh intents from overwriting one another.
        data = android.net.Uri.parse("coastalwx://widget/$widgetId/refresh")
    }
    views.setOnClickPendingIntent(
        R.id.widget_refresh,
        PendingIntent.getBroadcast(
            context,
            widgetId * 100 + 1,
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
        // Clearing the intent matters: a hidden view on a recycled tree otherwise keeps the
        // click target it was given the last time an alert was active.
        views.setOnClickPendingIntent(R.id.widget_storm_badge, null)
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
            requestCode = widgetId * 100 + 2,
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

private fun visibility(visible: Boolean): Int = if (visible) View.VISIBLE else View.GONE

// ── Parsing and formatting ──────────────────────────────────────────────────

// Moshi construction is not cheap and this runs inside goAsync() on every widget update.
private val moshi: Moshi by lazy { Moshi.Builder().build() }

private val dailyAdapter: JsonAdapter<List<DailyForecastItem>> by lazy {
    moshi.adapter(Types.newParameterizedType(List::class.java, DailyForecastItem::class.java))
}

private val hourlyAdapter: JsonAdapter<List<HourlyForecastItem>> by lazy {
    moshi.adapter(Types.newParameterizedType(List::class.java, HourlyForecastItem::class.java))
}

private fun parseDailyForecast(json: String): List<DailyForecastItem> = try {
    dailyAdapter.fromJson(json).orEmpty()
} catch (_: Exception) {
    emptyList()
}

private fun parseHourlyForecast(json: String): List<HourlyForecastItem> = try {
    hourlyAdapter.fromJson(json).orEmpty()
} catch (_: Exception) {
    emptyList()
}

/**
 * Tomorrow.io does not return day/night weather-code variants, so code 1000 at 2 AM is the
 * same "Clear" it is at noon. The cached daily entries do carry sunrise and sunset, which is
 * enough to stop the widget drawing a blazing sun in the middle of the night.
 */
private class DaylightWindows(private val windows: List<LongRange>) {

    fun isNight(epochMillis: Long): Boolean {
        if (windows.isEmpty()) return false
        return windows.none { epochMillis in it }
    }

    companion object {
        fun from(daily: List<DailyForecastItem>): DaylightWindows = DaylightWindows(
            daily.mapNotNull { day ->
                val sunrise = parseIsoMillis(day.values.sunriseTime)
                val sunset = parseIsoMillis(day.values.sunsetTime)
                if (sunrise == null || sunset == null || sunset <= sunrise) null
                else sunrise..sunset
            }
        )
    }
}

private fun parseIsoMillis(isoTime: String?): Long? {
    if (isoTime.isNullOrBlank()) return null
    return try {
        Instant.parse(isoTime).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

private val hourFormatter: DateTimeFormatter by lazy {
    // "h a" is what the app's timeline chart uses; matching it keeps the two surfaces aligned.
    DateTimeFormatter.ofPattern("h a", Locale.getDefault())
}

private val dayFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
}

private fun hourLabel(isoTime: String): String {
    val millis = parseIsoMillis(isoTime) ?: return "NA"
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(hourFormatter)
}

/**
 * Falls back to counting forward from today rather than showing a dash, because the daily
 * buckets are always consecutive days starting with today.
 */
private fun dayName(isoTime: String, offsetFromToday: Int): String {
    val millis = parseIsoMillis(isoTime)
    val zone = ZoneId.systemDefault()
    val date = if (millis != null) {
        Instant.ofEpochMilli(millis).atZone(zone)
    } else {
        Instant.now().atZone(zone).plusDays(offsetFromToday.toLong())
    }
    return date.format(dayFormatter)
}

private fun formatTemp(celsius: Double, isCelsius: Boolean): String =
    "${UnitFormatter.tempFromC(celsius, isCelsius).roundToInt()}°"

private fun Double?.toTempOrDash(isCelsius: Boolean): String =
    this?.let { "${UnitFormatter.tempFromC(it, isCelsius).roundToInt()}°" } ?: "--°"

/**
 * The app only prints a precipitation percentage once it reaches 15%, so anything below that
 * stays blank here too rather than filling the forecast rows with "3%".
 */
private fun formatPrecipitation(probability: Double?): String {
    val value = probability ?: return ""
    return if (value >= 15.0) "${value.roundToInt()}%" else ""
}

private val COMPASS_POINTS = arrayOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
)

private fun cardinalDirection(degrees: Double): String {
    val normalized = ((degrees % 360) + 360) % 360
    return COMPASS_POINTS[(normalized / 22.5).roundToInt() % 16]
}

private fun widgetVisual(weatherCode: Int, isNight: Boolean): WidgetVisual = when (weatherCode) {
    1000, 1100 -> WidgetVisual(
        if (isNight) R.drawable.ic_widget_clear_night else R.drawable.ic_widget_sunny,
        R.drawable.widget_bg_clear
    )
    1101 -> WidgetVisual(
        if (isNight) R.drawable.ic_widget_partly_cloudy_night
        else R.drawable.ic_widget_partly_cloudy,
        R.drawable.widget_bg_clear
    )
    1001, 1102 -> WidgetVisual(R.drawable.ic_widget_cloud, R.drawable.widget_bg_cloud)
    2000, 2100 -> WidgetVisual(R.drawable.ic_widget_fog, R.drawable.widget_bg_cloud)
    4000, 4001, 4200, 4201, 6000, 6001, 6200, 6201 -> {
        WidgetVisual(R.drawable.ic_widget_rain, R.drawable.widget_bg_rain)
    }
    // 7100 used to be missing here, so "Light Ice Pellets" got a snow icon as 7102 and a
    // plain cloud as 7100 — the same condition rendered two different ways.
    5000, 5001, 5100, 5101, 7000, 7100, 7101, 7102 -> {
        WidgetVisual(R.drawable.ic_widget_snow, R.drawable.widget_bg_snow)
    }
    8000 -> WidgetVisual(R.drawable.ic_widget_storm, R.drawable.widget_bg_storm)
    else -> WidgetVisual(R.drawable.ic_widget_cloud, R.drawable.widget_bg_cloud)
}

private fun widgetLocationLabel(location: SavedLocation): String {
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
        ageMinutes < 1 -> "Just now"
        ageMinutes < 60 -> "${ageMinutes}m ago"
        ageMinutes < 24 * 60 -> "${ageMinutes / 60}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}

private fun severityWeight(severity: String): Int = when (severity.lowercase()) {
    "extreme" -> 4
    "severe" -> 3
    "moderate" -> 2
    "minor" -> 1
    else -> 0
}

private data class WidgetVisual(val iconRes: Int, val backgroundRes: Int)

internal data class WidgetHazard(
    val label: String,
    val shortLabel: String,
    val accessibilityLabel: String,
    val alertId: String? = null,
    val destination: String? = null
)
