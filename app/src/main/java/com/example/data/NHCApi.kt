package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

// Schema matches https://www.nhc.noaa.gov/CurrentStorms.json — note that intensity/pressure
// are strings, latitude/longitude are "12.8N"-style strings (numeric twins carry the values),
// and there is no category field; category is derived from intensity in knots.
@JsonClass(generateAdapter = true)
data class NHCCurrentStorms(
    @Json(name = "activeStorms") val activeStorms: List<NHCStorm>?
)

@JsonClass(generateAdapter = true)
data class NHCStorm(
    @Json(name = "id") val id: String,
    @Json(name = "binNumber") val binNumber: String?,
    @Json(name = "name") val name: String,
    @Json(name = "classification") val classification: String?, // TD, TS, HU, STD, STS, PTC
    @Json(name = "intensity") val intensityKts: String?,        // Max sustained wind, knots
    @Json(name = "pressure") val pressureMb: String?,           // Minimum central pressure, mb
    @Json(name = "latitudeNumeric") val latitude: Double?,
    @Json(name = "longitudeNumeric") val longitude: Double?,
    @Json(name = "movementDir") val movementDirDegrees: Double?,
    @Json(name = "movementSpeed") val movementSpeedKts: Double?,
    @Json(name = "lastUpdate") val lastUpdate: String?,
    @Json(name = "publicAdvisory") val publicAdvisory: NHCProduct?,
    @Json(name = "forecastDiscussion") val forecastDiscussion: NHCProduct?,
    @Json(name = "forecastTrack") val forecastTrack: NHCProduct?
)

// Shared by text products (url) and GIS products (kmzFile) — absent fields stay null.
@JsonClass(generateAdapter = true)
data class NHCProduct(
    @Json(name = "advNum") val advNum: String?,
    @Json(name = "issuance") val issuance: String?,
    @Json(name = "url") val url: String?,
    @Json(name = "kmzFile") val kmzFile: String?
)

interface NHCApi {
    @GET("CurrentStorms.json")
    suspend fun getCurrentStorms(): Response<NHCCurrentStorms>
}

// Data holder for Track Point (to serialize past and future locations for the map coordinates)
@JsonClass(generateAdapter = true)
data class TrackPoint(
    val timeLabel: String,         // e.g. "Past 24h" or "Forecast 36h"
    val latitude: Double,
    val longitude: Double,
    val windSpeedKt: Int,
    val pressureMb: Int,
    val isForecast: Boolean,
    val dateText: String           // e.g. "May 25, 06:00 UTC"
)

@JsonClass(generateAdapter = true)
data class WindRadii(
    val radius34ktNm: Double,      // Nautical miles
    val radius50ktNm: Double,
    val radius64ktNm: Double
)

@JsonClass(generateAdapter = true)
data class StormDetail(
    val id: String,
    val name: String,
    val basin: String,
    val type: String,
    val category: Int,
    val latitude: Double,
    val longitude: Double,
    val windSpeedKts: Double,
    val pressureMb: Double,
    val direction: String,
    val speedMph: Double,
    val trackPoints: List<TrackPoint>,
    // Null when the advisory feed doesn't carry radii — the map skips the rings rather
    // than drawing made-up circles.
    val windRadii: WindRadii?,
    val watchWarningAreas: List<WatchWarningArea>,
    val advisoryText: String,
    val discussionText: String,
    val detailUrl: String
)

@JsonClass(generateAdapter = true)
data class WatchWarningArea(
    val type: String, // "hurricane_watch", "hurricane_warning", "tropical_storm_watch", "tropical_storm_warning"
    val segmentPath: List<TrackPoint> // Coastline vertices to highlight
)

object NHCService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://www.nhc.noaa.gov/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()

    val api: NHCApi = retrofit.create(NHCApi::class.java)

    fun classificationLabel(classification: String?): String = when (classification) {
        "HU" -> "Hurricane"
        "TS" -> "Tropical Storm"
        "TD" -> "Tropical Depression"
        "STS" -> "Subtropical Storm"
        "STD" -> "Subtropical Depression"
        "PTC" -> "Potential Tropical Cyclone"
        else -> "Tropical System"
    }

    fun basinLabel(stormId: String): String = when (stormId.take(2).lowercase()) {
        "al" -> "Atlantic"
        "ep" -> "Eastern Pacific"
        "cp" -> "Central Pacific"
        else -> "Atlantic"
    }

    /** Saffir-Simpson category from sustained winds in knots; 0 below hurricane force. */
    fun categoryFromKnots(kts: Double): Int = when {
        kts >= 137 -> 5
        kts >= 113 -> 4
        kts >= 96 -> 3
        kts >= 83 -> 2
        kts >= 64 -> 1
        else -> 0
    }

    fun compassDirection(degrees: Double?): String {
        if (degrees == null) return "—"
        val dirs = listOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        )
        val idx = (((degrees % 360 + 360) % 360 + 11.25) / 22.5).toInt() % 16
        return dirs[idx]
    }

    /** "011a" → "11A" — matches the "Advisory Number 11A" line inside the text products. */
    fun normalizeAdvNum(adv: String?): String? =
        adv?.trimStart('0')?.uppercase()?.takeIf { it.isNotBlank() }

    /** Downloads an NHC text product page (.shtml) and extracts the bulletin from its <pre> block. */
    suspend fun fetchProductText(url: String?): String? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val html = resp.body?.string() ?: return@withContext null
                val pre = Regex("(?s)<pre[^>]*>(.*?)</pre>").find(html)?.groupValues?.get(1)
                    ?: return@withContext null
                pre.replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads the forecast-track KMZ (a zip holding one KML) and parses its track points. */
    suspend fun fetchForecastTrack(kmzUrl: String?): List<TrackPoint>? = withContext(Dispatchers.IO) {
        if (kmzUrl.isNullOrBlank()) return@withContext null
        try {
            client.newCall(Request.Builder().url(kmzUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                val kml = ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                    generateSequence { zin.nextEntry }
                        .firstOrNull { it.name.endsWith(".kml", ignoreCase = true) }
                        ?.let { zin.readBytes().toString(Charsets.UTF_8) }
                } ?: return@withContext null
                NHCTrackParser.parseTrackKml(kml).takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun toStormDetail(storm: NHCStorm): StormDetail? {
        val lat = storm.latitude ?: return null
        val lon = storm.longitude ?: return null
        val windKts = storm.intensityKts?.toDoubleOrNull() ?: 0.0
        val pressure = storm.pressureMb?.toDoubleOrNull() ?: 0.0
        val type = classificationLabel(storm.classification)
        val advNum = storm.publicAdvisory?.advNum
        val updated = storm.lastUpdate ?: "unknown"

        val summary = buildString {
            append("$type ${storm.name}")
            if (advNum != null) append(" — Advisory #$advNum")
            append("\nLast NHC update: $updated\n\n")
            append("Position: ${"%.1f".format(lat)}N ${"%.1f".format(kotlin.math.abs(lon))}${if (lon < 0) "W" else "E"}\n")
            append("Max sustained winds: ${windKts.toInt()} kt\n")
            append("Minimum central pressure: ${pressure.toInt()} mb\n\n")
            append("Open the official NHC advisory page for full text, watches, and warnings.")
        }

        return StormDetail(
            id = storm.id,
            name = storm.name,
            basin = basinLabel(storm.id),
            type = type,
            category = categoryFromKnots(windKts),
            latitude = lat,
            longitude = lon,
            windSpeedKts = windKts,
            pressureMb = pressure,
            direction = compassDirection(storm.movementDirDegrees),
            speedMph = (storm.movementSpeedKts ?: 0.0) * 1.15078,
            // Fallback track/text — WeatherRepository swaps in the fetched advisory,
            // discussion, and KMZ forecast track when those products are reachable.
            trackPoints = listOf(
                TrackPoint("Current Center", lat, lon, windKts.toInt(), pressure.toInt(), false, updated)
            ),
            windRadii = null,
            watchWarningAreas = emptyList(),
            advisoryText = summary,
            discussionText = "The full forecast discussion is published on the NHC site.\n\n" +
                "Tap \"View Official NOAA NHC Public Advisory Page\" above to read it.",
            detailUrl = storm.publicAdvisory?.url
                ?: storm.forecastDiscussion?.url
                ?: "https://www.nhc.noaa.gov/"
        )
    }
}

object NHCTrackParser {
    /**
     * The TRACK KMZ holds one KML containing LineString placemarks (the connecting
     * lines, skipped here) and Point placemarks for the initial position plus each
     * forecast hour. The values we need — forecast hour, winds, pressure, valid time —
     * live only in each Point's HTML description block, so this parses that text
     * rather than the XML structure.
     */
    fun parseTrackKml(kml: String): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        for (pm in kml.split("<Placemark").drop(1)) {
            val pointXml = pm.substringAfter("<Point", "")
            if (pointXml.isEmpty()) continue
            val coords = Regex("<coordinates>\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)").find(pointXml) ?: continue
            val lon = coords.groupValues[1].toDoubleOrNull() ?: continue
            val lat = coords.groupValues[2].toDoubleOrNull() ?: continue

            val isInitial = pm.contains("Advisory Information")
            val tau = Regex("(\\d+)\\s*hr Forecast").find(pm)?.groupValues?.get(1)?.toIntOrNull()
            if (!isInitial && tau == null) continue

            val windKt = Regex("Maximum Wind:\\s*(\\d+)\\s*knots").find(pm)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val pressureMb = Regex("Minimum Pressure:\\s*(\\d+)\\s*mb").find(pm)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val validAt = Regex("Valid at:\\s*([^<]+)").find(pm)
                ?.groupValues?.get(1)?.trim().orEmpty()

            points.add(
                TrackPoint(
                    timeLabel = if (isInitial) "Current Center" else "${tau}h Forecast",
                    latitude = lat,
                    longitude = lon,
                    windSpeedKt = windKt,
                    pressureMb = pressureMb,
                    isForecast = !isInitial,
                    dateText = validAt
                )
            )
        }
        return points
    }
}
