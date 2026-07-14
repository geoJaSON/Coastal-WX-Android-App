package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class NWSAlertsResponse(
    @Json(name = "features") val features: List<NWSAlertFeature>?
)

@JsonClass(generateAdapter = true)
data class NWSAlertFeature(
    @Json(name = "id") val id: String?,
    @Json(name = "properties") val properties: NWSAlertProperties
)

@JsonClass(generateAdapter = true)
data class NWSAlertProperties(
    @Json(name = "id") val id: String?,
    @Json(name = "event") val event: String?,
    @Json(name = "severity") val severity: String?,        // Extreme, Severe, Moderate, Minor, Unknown
    @Json(name = "certainty") val certainty: String?,
    @Json(name = "urgency") val urgency: String?,
    @Json(name = "headline") val headline: String?,
    @Json(name = "description") val description: String?,
    @Json(name = "instruction") val instruction: String?,
    @Json(name = "senderName") val senderName: String?,
    @Json(name = "effective") val effective: String?,
    @Json(name = "expires") val expires: String?,
    @Json(name = "areaDesc") val areaDesc: String?
)

interface NWSApi {
    @GET("alerts/active")
    suspend fun getActiveAlerts(
        @Query("point") point: String  // "lat,lon"
    ): Response<NWSAlertsResponse>
}

object NWSService {
    // NWS asks for a descriptive User-Agent identifying the app.
    private const val USER_AGENT = "CoastalWX/1.0 (personal use)"

    private val userAgentInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/geo+json")
            .build()
        chain.proceed(req)
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(userAgentInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val api: NWSApi = Retrofit.Builder()
        .baseUrl("https://api.weather.gov/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
        .build()
        .create(NWSApi::class.java)

    fun severityRank(severity: String?): String = when (severity?.lowercase()) {
        "extreme" -> "extreme"
        "severe" -> "severe"
        "moderate" -> "moderate"
        else -> "minor"
    }

    fun toActiveAlert(
        feature: NWSAlertFeature,
        storedId: String,
        location: SavedLocation
    ): ActiveAlert {
        val props = feature.properties
        return ActiveAlert(
            id = storedId,
            title = props.event ?: props.headline ?: "Weather Alert",
            severity = severityRank(props.severity),
            description = props.description?.trim().orEmpty().ifBlank {
                props.headline?.trim().orEmpty()
            },
            headline = props.headline?.trim()?.takeIf { it.isNotBlank() },
            urgency = props.urgency,
            certainty = props.certainty,
            effective = props.effective,
            expires = props.expires,
            areaDesc = props.areaDesc?.trim()?.takeIf { it.isNotBlank() },
            instruction = props.instruction?.trim()?.takeIf { it.isNotBlank() },
            senderName = props.senderName?.trim()?.takeIf { it.isNotBlank() },
            sourceUrl = (feature.id ?: props.id)?.takeIf { it.startsWith("http") },
            locationId = location.id,
            locationName = location.name
        )
    }
}
