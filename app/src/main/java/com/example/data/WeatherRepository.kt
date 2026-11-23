package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.widget.WeatherWidgetProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Owns every network->database sync path so the same logic serves both the ViewModel
 * (app open) and WeatherSyncWorker (background). UI state stays in the ViewModel.
 */
class WeatherRepository private constructor(private val context: Context) {

    private val db = WeatherDatabase.getDatabase(context)

    private val moshi = Moshi.Builder().build()
    private val hourlyType = Types.newParameterizedType(List::class.java, HourlyForecastItem::class.java)
    val hourlyAdapter = moshi.adapter<List<HourlyForecastItem>>(hourlyType)
    private val dailyType = Types.newParameterizedType(List::class.java, DailyForecastItem::class.java)
    val dailyAdapter = moshi.adapter<List<DailyForecastItem>>(dailyType)
    private val trackType = Types.newParameterizedType(List::class.java, TrackPoint::class.java)
    private val trackAdapter = moshi.adapter<List<TrackPoint>>(trackType)

    val tomorrowApi: TomorrowApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.tomorrow.io/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TomorrowApi::class.java)
    }

    sealed interface ForecastResult {
        data class Success(
            val cache: WeatherCache,
            val hourly: List<HourlyForecastItem>,
            val daily: List<DailyForecastItem>,
            val minutely: List<MinutelyForecastItem>
        ) : ForecastResult
        data object NoApiKey : ForecastResult
        data object Failure : ForecastResult
    }

    sealed interface StormSyncResult {
        val storms: List<StormDetail>
        val lastVerifiedMillis: Long?

        data class Fresh(
            override val storms: List<StormDetail>,
            override val lastVerifiedMillis: Long
        ) : StormSyncResult

        data class Cached(
            override val storms: List<StormDetail>,
            override val lastVerifiedMillis: Long?,
            val reason: String
        ) : StormSyncResult

        data class Unavailable(val reason: String) : StormSyncResult {
            override val storms: List<StormDetail> = emptyList()
            override val lastVerifiedMillis: Long? = null
        }
    }

    // ---------- Tomorrow.io forecast ----------
    suspend fun refreshForecast(location: SavedLocation): ForecastResult {
        val apiKey = PreferencesHelper.getApiKey(context)
        if (apiKey.isBlank()) return ForecastResult.NoApiKey

        return try {
            val response = tomorrowApi.getForecast(
                location = "${location.latitude},${location.longitude}",
                apiKey = apiKey,
                units = "metric"
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) return ForecastResult.Failure

            val minutelyItems = body.timelines?.minutely ?: emptyList()
            val hourlyItems = body.timelines?.hourly ?: emptyList()
            val dailyItems = body.timelines?.daily ?: emptyList()
            val current = hourlyItems.firstOrNull()?.values ?: return ForecastResult.Failure

            val updatedCache = WeatherCache(
                locationId = location.id,
                currentTemp = current.temperature ?: 0.0,
                feelsLike = current.temperatureApparent ?: current.temperature ?: 0.0,
                humidity = current.humidity ?: 0.0,
                windSpeed = current.windSpeed ?: 0.0,
                windDirection = current.windDirection ?: 0.0,
                uvIndex = current.uvIndex ?: 0.0,
                visibility = current.visibility ?: 10.0,
                pressure = current.pressureSurfaceLevel ?: 1013.25,
                weatherCode = current.weatherCode ?: 1000,
                lastUpdatedMillis = System.currentTimeMillis(),
                hourlyJson = hourlyAdapter.toJson(hourlyItems.take(24)),
                dailyJson = dailyAdapter.toJson(dailyItems.take(5))
            )
            db.weatherCacheDao.insertWeatherCache(updatedCache)
            WeatherWidgetProvider.triggerUpdate(context)

            ForecastResult.Success(
                cache = updatedCache,
                hourly = hourlyItems.take(24),
                daily = dailyItems.take(5),
                minutely = minutelyItems.take(60)
            )
        } catch (e: Exception) {
            ForecastResult.Failure
        }
    }

    suspend fun getCachedForecast(locationId: Long): WeatherCache? =
        db.weatherCacheDao.getWeatherCacheByLocation(locationId)

    // ---------- NWS alerts ----------
    suspend fun syncNWSAlerts(loc: SavedLocation) {
        try {
            val resp = NWSService.api.getActiveAlerts(point = "${loc.latitude},${loc.longitude}")
            if (!resp.isSuccessful) return
            val features = resp.body()?.features ?: emptyList()

            // Stable per-location id namespace so we can prune stale alerts.
            val prefix = "nws_${loc.id}_"
            val existing = db.activeAlertDao.getAllAlerts().filter { it.id.startsWith(prefix) }
            val incomingIds = features.mapNotNull { feature ->
                (feature.properties.id ?: feature.id)?.let { prefix + it }
            }.toSet()

            // Remove alerts that NWS no longer reports as active for this location.
            existing.filter { it.id !in incomingIds }.forEach { stale ->
                db.activeAlertDao.deleteAlertById(stale.id)
            }

            // Insert new alerts; skip ones the user already dismissed.
            val dismissedIds = existing.filter { it.dismissed }.map { it.id }.toSet()
            val toInsert = features.mapNotNull { f ->
                val props = f.properties
                val rawId = props.id ?: f.id ?: return@mapNotNull null
                val storedId = prefix + rawId
                if (storedId in dismissedIds) return@mapNotNull null
                NWSService.toActiveAlert(f, storedId, loc)
            }
            if (toInsert.isNotEmpty()) {
                // Filter out ones that already exist (avoid re-notifying for unchanged alerts).
                val newOnes = toInsert.filter { incoming ->
                    existing.none { it.id == incoming.id }
                }
                db.activeAlertDao.insertAlerts(toInsert)
                val mostSevere = newOnes.maxByOrNull { severityWeight(it.severity) }
                val threshold = severityWeight(PreferencesHelper.getSevereAlertSeverityThreshold(context))
                if (mostSevere != null && severityWeight(mostSevere.severity) >= threshold) {
                    val notificationBody = mostSevere.headline
                        ?: mostSevere.instruction
                        ?: mostSevere.description
                    sendAlertNotification(
                        alertId = mostSevere.id,
                        title = mostSevere.title,
                        body = notificationBody.take(240)
                    )
                }
            }
        } catch (e: Exception) {
            // Network/parse failures are non-fatal; keep stale cached alerts.
        }
    }

    private fun severityWeight(s: String): Int = when (s.lowercase()) {
        "extreme" -> 4
        "severe" -> 3
        "moderate" -> 2
        "minor" -> 1
        else -> 0
    }

    // ---------- Hurricane / NHC ----------
    suspend fun syncStorms(): StormSyncResult {
        val rawStorms = try {
            val response = NHCService.api.getCurrentStorms()
            if (!response.isSuccessful || response.body() == null) {
                return cachedStormResult("The National Hurricane Center did not return current data.")
            }
            response.body()?.activeStorms ?: emptyList()
        } catch (e: Exception) {
            return cachedStormResult("The National Hurricane Center could not be reached.")
        }

        // CurrentStorms.json combines Atlantic, Eastern Pacific, and Central Pacific systems.
        // Filter before product enrichment so Pacific advisories are neither shown nor downloaded.
        val atlanticStorms = rawStorms.filter { NHCService.isAtlanticStorm(it.id) }
        val cachedById = db.stormCacheDao.getAllStorms().associateBy { it.id }
        val storms = atlanticStorms.mapNotNull { raw ->
            val base = NHCService.toStormDetail(raw) ?: return@mapNotNull null
            enrichWithProducts(base, raw, cachedById[base.id])
        }

        db.stormCacheDao.clearAllStorms()
        if (storms.isNotEmpty()) {
            db.stormCacheDao.insertStorms(storms.map { s ->
                StormCache(
                    id = s.id,
                    name = s.name,
                    basin = s.basin,
                    type = s.type,
                    category = s.category,
                    latitude = s.latitude,
                    longitude = s.longitude,
                    windSpeedKt = s.windSpeedKts,
                    pressureMb = s.pressureMb,
                    direction = s.direction,
                    speedMph = s.speedMph,
                    trackJson = trackAdapter.toJson(s.trackPoints),
                    advisoryText = s.advisoryText,
                    discussionText = s.discussionText,
                    detailUrl = s.detailUrl
                )
            })
        }

        pruneStaleProximityAlerts(storms)
        checkLocationsProximityAlert(storms)
        WeatherWidgetProvider.triggerUpdate(context)
        val verifiedAt = System.currentTimeMillis()
        PreferencesHelper.saveLastSuccessfulStormSyncMillis(context, verifiedAt)
        return StormSyncResult.Fresh(storms, verifiedAt)
    }

    private suspend fun cachedStormResult(reason: String): StormSyncResult {
        val cached = cachedStormDetails()
        val lastVerified = PreferencesHelper.getLastSuccessfulStormSyncMillis(context)
            .takeIf { it > 0L }
        return if (cached.isNotEmpty() || lastVerified != null) {
            StormSyncResult.Cached(cached, lastVerified, reason)
        } else {
            StormSyncResult.Unavailable(reason)
        }
    }

    /**
     * Advisory text, discussion, and the forecast track only change when NHC issues a
     * new advisory (~every 3 hours), so the three product fetches are skipped while the
     * cached copy still references the current advisory number.
     */
    private suspend fun enrichWithProducts(
        base: StormDetail,
        raw: NHCStorm,
        cached: StormCache?
    ): StormDetail {
        val advNum = NHCService.normalizeAdvNum(raw.publicAdvisory?.advNum)
        val cacheIsCurrent = cached != null && advNum != null &&
            cached.advisoryText.contains("Advisory Number $advNum", ignoreCase = true)

        if (cacheIsCurrent) {
            val cachedTrack = try {
                trackAdapter.fromJson(cached!!.trackJson) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            return base.copy(
                advisoryText = cached!!.advisoryText,
                discussionText = cached.discussionText,
                trackPoints = cachedTrack.ifEmpty { base.trackPoints }
            )
        }

        return base.copy(
            advisoryText = NHCService.fetchProductText(raw.publicAdvisory?.url) ?: base.advisoryText,
            discussionText = NHCService.fetchProductText(raw.forecastDiscussion?.url) ?: base.discussionText,
            trackPoints = NHCService.fetchForecastTrack(raw.forecastTrack?.kmzFile) ?: base.trackPoints
        )
    }

    private suspend fun cachedStormDetails(): List<StormDetail> =
        db.stormCacheDao.getAllStorms()
            .filter { NHCService.isAtlanticStorm(it.id) }
            .map { c ->
                StormDetail(
                    id = c.id,
                    name = c.name,
                    basin = c.basin,
                    type = c.type,
                    category = c.category,
                    latitude = c.latitude,
                    longitude = c.longitude,
                    windSpeedKts = c.windSpeedKt,
                    pressureMb = c.pressureMb,
                    direction = c.direction,
                    speedMph = c.speedMph,
                    trackPoints = try {
                        trackAdapter.fromJson(c.trackJson) ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    },
                    windRadii = null,
                    watchWarningAreas = emptyList(),
                    advisoryText = c.advisoryText,
                    discussionText = c.discussionText,
                    detailUrl = c.detailUrl
                )
            }

    /**
     * Drops proximity alerts for storms no longer in the feed — including the
     * "storm_prox_SIM2026_*" rows left behind by the removed simulation mode.
     */
    private suspend fun pruneStaleProximityAlerts(currentStorms: List<StormDetail>) {
        val validPrefixes = currentStorms.map { "storm_prox_${it.id}_" }
        db.activeAlertDao.getAllAlerts()
            .filter { alert ->
                alert.id.startsWith("storm_prox_") &&
                    validPrefixes.none { alert.id.startsWith(it) }
            }
            .forEach { db.activeAlertDao.deleteAlertById(it.id) }
    }

    private suspend fun checkLocationsProximityAlert(storms: List<StormDetail>) {
        if (storms.isEmpty()) return
        val locations = db.savedLocationDao.getAllLocationsFlow().first()
        val radiusLimit = PreferencesHelper.getProximityAlertRadiusMiles(context)

        storms.forEach { storm ->
            locations.forEach { loc ->
                val dist = calculateDistanceMiles(loc.latitude, loc.longitude, storm.latitude, storm.longitude)
                if (dist <= radiusLimit) {
                    val alertId = "storm_prox_${storm.id}_${loc.id}"
                    val exists = db.activeAlertDao.getAllAlerts().any { it.id == alertId }
                    if (!exists) {
                        val newAlert = ActiveAlert(
                            id = alertId,
                            title = "URGENT: ${storm.type} ${storm.name} APPROACHING",
                            severity = "extreme",
                            description = "Storm core estimated at ${dist.toInt()} miles from ${loc.name}. Sustained winds: ${storm.windSpeedKts.toInt()} knots.",
                            headline = "${storm.type} ${storm.name} is within your configured proximity radius.",
                            urgency = "Immediate",
                            certainty = "Observed",
                            areaDesc = loc.name,
                            instruction = "Monitor official NHC and local emergency-management guidance. Secure loose outdoor items and review your evacuation plan.",
                            sourceUrl = storm.detailUrl,
                            locationId = loc.id,
                            locationName = loc.name
                        )
                        db.activeAlertDao.insertAlerts(listOf(newAlert))
                        sendAlertNotification(
                            alertId = newAlert.id,
                            title = "URGENT TROPICAL WARNING",
                            body = newAlert.headline ?: newAlert.description
                        )
                    }
                }
            }
        }
    }

    fun calculateDistanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMiles = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMiles * c
    }

    // ---------- Notifications ----------
    fun sendAlertNotification(alertId: String, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ALERT_ID, alertId)
        }
        val pi = PendingIntent.getActivity(
            context, alertId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(alertId.hashCode(), builder.build())
    }

    fun createAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Severe Weather Warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Severe lightning, wind, and hurricane hazard proximity alarms"
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val ALERT_CHANNEL_ID = "aero_life_safety_channel"

        @Volatile
        private var INSTANCE: WeatherRepository? = null

        fun getInstance(context: Context): WeatherRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WeatherRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
