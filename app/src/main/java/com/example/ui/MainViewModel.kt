package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val db = WeatherDatabase.getDatabase(context)
    private val repository = WeatherRepository.getInstance(context)

    private val moshi = Moshi.Builder().build()
    private val stationListAdapter = moshi.adapter(TideStationListResponse::class.java)

    // 1. Saved locations state
    val savedLocations: StateFlow<List<SavedLocation>> = db.savedLocationDao.getAllLocationsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 2. Active location tracking
    private val _activeLocationId = MutableStateFlow<Long>(-1L)
    val activeLocationId: StateFlow<Long> = _activeLocationId.asStateFlow()

    private val _currentLocation = MutableStateFlow<SavedLocation?>(null)
    val currentLocation: StateFlow<SavedLocation?> = _currentLocation.asStateFlow()

    // 3. Current Weather and Forecast UI States
    private val _weatherState = MutableStateFlow<WeatherUIState>(WeatherUIState.Loading)
    val weatherState: StateFlow<WeatherUIState> = _weatherState.asStateFlow()

    // 4. Alerts and Tropics — NWS alerts are namespaced per location ("nws_<id>_…"), and only
    // the active location's are shown so banners from a previously viewed location don't
    // linger. Storm proximity alerts are global by design.
    val activeAlerts: StateFlow<List<ActiveAlert>> =
        combine(db.activeAlertDao.getActiveAlertsFlow(), _activeLocationId) { alerts, activeId ->
            alerts.filter { alert ->
                alert.id.startsWith("storm_prox_") || alert.id.startsWith("nws_${activeId}_")
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _stormState = MutableStateFlow<StormUIState>(StormUIState.Loading)
    val stormState: StateFlow<StormUIState> = _stormState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _showConnectionTestResult = MutableStateFlow<Pair<Boolean, String>?>(null)
    val showConnectionTestResult: StateFlow<Pair<Boolean, String>?> = _showConnectionTestResult.asStateFlow()

    // 5. Tides
    private val _tideSummary = MutableStateFlow<TideSummary?>(null)
    val tideSummary: StateFlow<TideSummary?> = _tideSummary.asStateFlow()

    @Volatile private var cachedTideStations: List<TideStation>? = null

    // 6. Geocoding / city search
    private val _geocodeResults = MutableStateFlow<List<NominatimResult>>(emptyList())
    val geocodeResults: StateFlow<List<NominatimResult>> = _geocodeResults.asStateFlow()

    private val _geocodeSearching = MutableStateFlow(false)
    val geocodeSearching: StateFlow<Boolean> = _geocodeSearching.asStateFlow()

    private var geocodeJob: Job? = null

    // 7. GPS request state
    private val _gpsRequestState = MutableStateFlow<GpsRequestState>(GpsRequestState.Idle)
    val gpsRequestState: StateFlow<GpsRequestState> = _gpsRequestState.asStateFlow()

    init {
        repository.createAlertNotificationChannel()

        // Setup initial default database elements if none exist
        viewModelScope.launch(Dispatchers.IO) {
            val count = db.savedLocationDao.getAllLocationsFlow().first().size
            if (count == 0) {
                val primaryId = db.savedLocationDao.insertLocation(
                    SavedLocation(name = "Miami, FL", latitude = 25.7617, longitude = -80.1918)
                )
                db.savedLocationDao.insertLocation(
                    SavedLocation(name = "Outer Banks, NC", latitude = 35.5585, longitude = -75.4665)
                )
                db.savedLocationDao.insertLocation(
                    SavedLocation(name = "New York, NY", latitude = 40.7128, longitude = -74.0060)
                )
                PreferencesHelper.saveActiveLocationId(context, primaryId)
                _activeLocationId.value = primaryId
            } else {
                var activeId = PreferencesHelper.getActiveLocationId(context)
                if (activeId == -1L) {
                    val first = db.savedLocationDao.getAllLocationsFlow().first().firstOrNull()
                    if (first != null) {
                        PreferencesHelper.saveActiveLocationId(context, first.id)
                        activeId = first.id
                    }
                }
                _activeLocationId.value = activeId
            }

            // If GPS permission is already granted, refresh the GPS row coords silently.
            if (LocationProvider.hasPermission(context)) {
                refreshGpsCoordinatesSilently()
            }

            syncActiveLocationData()
            startPeriodicRefresh()
        }
    }

    private fun startPeriodicRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val intervalMins = PreferencesHelper.getRefreshIntervalMinutes(context)
                delay(TimeUnit.MINUTES.toMillis(intervalMins.toLong()))
                if (LocationProvider.hasPermission(context)) {
                    refreshGpsCoordinatesSilently()
                }
                refreshAllData(silent = true)
            }
        }
    }

    fun selectActiveLocation(locationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            PreferencesHelper.saveActiveLocationId(context, locationId)
            _activeLocationId.value = locationId
            _tideSummary.value = null
            syncActiveLocationData()
        }
    }

    suspend fun syncActiveLocationData() {
        val activeId = _activeLocationId.value
        if (activeId == -1L) return

        val location = db.savedLocationDao.getLocationById(activeId) ?: return
        _currentLocation.value = location

        val cache = repository.getCachedForecast(activeId)
        if (cache != null) {
            _weatherState.value = uiStateFromCache(cache)
        } else {
            _weatherState.value = WeatherUIState.Loading
        }

        refreshAllData(silent = true)
    }

    fun refreshAllData(silent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!silent) _isRefreshing.value = true

            val loc = _currentLocation.value
            if (loc != null) {
                when (val result = repository.refreshForecast(loc)) {
                    is WeatherRepository.ForecastResult.Success -> {
                        _weatherState.value = WeatherUIState.Success(
                            currentTemp = result.cache.currentTemp,
                            feelsLike = result.cache.feelsLike,
                            humidity = result.cache.humidity,
                            windSpeed = result.cache.windSpeed,
                            windDirection = result.cache.windDirection,
                            uvIndex = result.cache.uvIndex,
                            visibility = result.cache.visibility,
                            pressure = result.cache.pressure,
                            weatherCode = result.cache.weatherCode,
                            lastUpdatedMillis = result.cache.lastUpdatedMillis,
                            hourlyForecast = result.hourly,
                            dailyForecast = result.daily,
                            minutelyForecast = result.minutely,
                            isCached = false
                        )
                    }
                    is WeatherRepository.ForecastResult.NoApiKey -> {
                        _weatherState.value = WeatherUIState.NoApiKey
                    }
                    is WeatherRepository.ForecastResult.Failure -> {
                        markActiveStateAsCachedOrError()
                    }
                }
            } else if (PreferencesHelper.getApiKey(context).isBlank()) {
                _weatherState.value = WeatherUIState.NoApiKey
            }

            // Side-channel syncs run in parallel, but the refresh indicator waits for them.
            coroutineScope {
                launch { loc?.let { repository.syncNWSAlerts(it) } }
                launch { syncTides(loc) }
                launch { _stormState.value = stormUiStateFrom(repository.syncStorms()) }
            }

            _isRefreshing.value = false
        }
    }

    fun refreshStorms() {
        viewModelScope.launch(Dispatchers.IO) {
            _stormState.value = StormUIState.Loading
            _stormState.value = stormUiStateFrom(repository.syncStorms())
        }
    }

    suspend fun getAlertById(alertId: String): ActiveAlert? =
        db.activeAlertDao.getAlertById(alertId)

    private fun uiStateFromCache(cache: WeatherCache): WeatherUIState.Success {
        val hourly = try { repository.hourlyAdapter.fromJson(cache.hourlyJson) ?: emptyList() } catch (e: Exception) { emptyList() }
        val daily = try { repository.dailyAdapter.fromJson(cache.dailyJson) ?: emptyList() } catch (e: Exception) { emptyList() }
        return WeatherUIState.Success(
            currentTemp = cache.currentTemp,
            feelsLike = cache.feelsLike,
            humidity = cache.humidity,
            windSpeed = cache.windSpeed,
            windDirection = cache.windDirection,
            uvIndex = cache.uvIndex,
            visibility = cache.visibility,
            pressure = cache.pressure,
            weatherCode = cache.weatherCode,
            lastUpdatedMillis = cache.lastUpdatedMillis,
            hourlyForecast = hourly,
            dailyForecast = daily,
            isCached = true
        )
    }

    private suspend fun markActiveStateAsCachedOrError() {
        val state = _weatherState.value
        if (state is WeatherUIState.Success) {
            _weatherState.value = state.copy(isCached = true)
        } else {
            val cache = repository.getCachedForecast(_activeLocationId.value)
            if (cache != null) {
                _weatherState.value = uiStateFromCache(cache)
            } else {
                _weatherState.value = WeatherUIState.Error(
                    "Couldn't reach Tomorrow.io. Check your connection — or verify your API key in Settings."
                )
            }
        }
    }

    // ---------- Tides ----------
    private suspend fun ensureTideStations(): List<TideStation>? {
        cachedTideStations?.let { return it }
        val cacheFile = File(context.filesDir, "tide_stations.json")
        if (cacheFile.exists()) {
            try {
                val json = cacheFile.readText()
                val resp = stationListAdapter.fromJson(json)
                val list = resp?.stations
                if (list != null && list.isNotEmpty()) {
                    cachedTideStations = list
                    return list
                }
            } catch (e: Exception) {
                // fall through to network fetch
            }
        }
        return try {
            val resp = TidesService.stationApi.getStations()
            if (resp.isSuccessful) {
                val list = resp.body()?.stations ?: emptyList()
                if (list.isNotEmpty()) {
                    cachedTideStations = list
                    try {
                        cacheFile.writeText(stationListAdapter.toJson(TideStationListResponse(list)))
                    } catch (e: Exception) { /* cache write best-effort */ }
                }
                list.ifEmpty { null }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun syncTides(loc: SavedLocation?) {
        if (loc == null) {
            _tideSummary.value = null
            return
        }
        val stations = ensureTideStations() ?: return
        val station = TidesService.findNearestStation(stations, loc.latitude, loc.longitude)
        if (station == null) {
            _tideSummary.value = null
            return
        }
        try {
            val resp = TidesService.predictionsApi.getPredictions(
                stationId = station.id,
                beginDate = TidesService.todayBeginDate()
            )
            if (resp.isSuccessful) {
                val preds = resp.body()?.predictions ?: emptyList()
                _tideSummary.value = if (preds.isNotEmpty()) {
                    TideSummary(station.name, station.id, preds)
                } else null
            }
        } catch (e: Exception) {
            // leave existing summary as-is
        }
    }

    // ---------- Location modifiers ----------
    fun addNewLocation(name: String, lat: Double, lon: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = db.savedLocationDao.insertLocation(
                SavedLocation(name = name, latitude = lat, longitude = lon)
            )
            selectActiveLocation(newId)
        }
    }

    fun addLocationFromGeocode(result: NominatimResult) {
        val lat = result.lat.toDoubleOrNull() ?: return
        val lon = result.lon.toDoubleOrNull() ?: return
        val nice = result.address?.shortLabel().orEmpty().ifBlank {
            result.displayName.substringBefore(",")
        }
        addNewLocation(nice, lat, lon)
        clearGeocodeResults()
    }

    fun deleteLocation(loc: SavedLocation) {
        viewModelScope.launch(Dispatchers.IO) {
            db.savedLocationDao.deleteLocation(loc)
            db.weatherCacheDao.deleteWeatherCache(loc.id)
            db.activeAlertDao.deleteAlertsByPrefix("nws_${loc.id}_")
            if (_activeLocationId.value == loc.id) {
                val remains = db.savedLocationDao.getAllLocationsFlow().first().firstOrNull()
                if (remains != null) {
                    selectActiveLocation(remains.id)
                } else {
                    _activeLocationId.value = -1L
                    _currentLocation.value = null
                    _weatherState.value = WeatherUIState.NoLocations
                }
            }
        }
    }

    fun dismissAlertBanner(alertId: String) {
        viewModelScope.launch {
            db.activeAlertDao.dismissAlert(alertId)
        }
    }

    /**
     * Caller (UI) is responsible for ensuring ACCESS_FINE/COARSE_LOCATION is granted before calling this.
     */
    fun useCurrentLocation() {
        viewModelScope.launch(Dispatchers.IO) {
            _gpsRequestState.value = GpsRequestState.Loading

            if (!LocationProvider.hasPermission(context)) {
                _gpsRequestState.value = GpsRequestState.Failed("Location permission not granted.")
                return@launch
            }
            if (!LocationProvider.isLocationEnabled(context)) {
                _gpsRequestState.value = GpsRequestState.Failed("Location services are disabled. Enable them in system settings.")
                return@launch
            }

            val fix = LocationProvider.getCurrentLocation(context)
            if (fix == null) {
                _gpsRequestState.value = GpsRequestState.Failed("Couldn't get a location fix. Try again outdoors or with location services on.")
                return@launch
            }

            val niceLabel = try {
                val resp = GeocodingService.api.reverse(fix.latitude, fix.longitude)
                if (resp.isSuccessful) {
                    val label = resp.body()?.address?.shortLabel().orEmpty()
                    if (label.isNotBlank()) "Current Location ($label)" else "Current Location"
                } else "Current Location"
            } catch (e: Exception) {
                "Current Location"
            }

            val existing = db.savedLocationDao.getGPSLocation()
            val newId = if (existing != null) {
                db.savedLocationDao.insertLocation(
                    existing.copy(
                        name = niceLabel,
                        latitude = fix.latitude,
                        longitude = fix.longitude
                    )
                )
            } else {
                db.savedLocationDao.insertLocation(
                    SavedLocation(
                        name = niceLabel,
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        isGPS = true
                    )
                )
            }

            PreferencesHelper.saveActiveLocationId(context, newId)
            _activeLocationId.value = newId
            _gpsRequestState.value = GpsRequestState.Success
            syncActiveLocationData()
        }
    }

    fun clearGpsRequestState() {
        _gpsRequestState.value = GpsRequestState.Idle
    }

    /**
     * Refresh the cached GPS location's coordinates without prompting the user.
     * Only updates if a GPS row already exists and permissions are still granted.
     */
    private suspend fun refreshGpsCoordinatesSilently() {
        if (!LocationProvider.hasPermission(context)) return
        val existing = db.savedLocationDao.getGPSLocation() ?: return
        val fix = LocationProvider.getCurrentLocation(context) ?: return

        // Skip update if the device hasn't moved meaningfully (avoids hammering reverse-geocode).
        val moved = repository.calculateDistanceMiles(
            existing.latitude, existing.longitude, fix.latitude, fix.longitude
        )
        if (moved < 1.0 && existing.name.startsWith("Current Location")) return

        db.savedLocationDao.insertLocation(
            existing.copy(latitude = fix.latitude, longitude = fix.longitude)
        )
        if (_activeLocationId.value == existing.id) {
            _currentLocation.value = db.savedLocationDao.getLocationById(existing.id)
        }
    }

    // ---------- City search (forward geocoding) ----------
    fun searchCity(query: String) {
        geocodeJob?.cancel()
        if (query.isBlank() || query.length < 2) {
            _geocodeResults.value = emptyList()
            _geocodeSearching.value = false
            return
        }
        geocodeJob = viewModelScope.launch(Dispatchers.IO) {
            _geocodeSearching.value = true
            // Light debounce -- Nominatim's policy asks for ~1 req/sec.
            delay(450)
            try {
                val resp = GeocodingService.api.search(query = query)
                if (resp.isSuccessful) {
                    _geocodeResults.value = resp.body() ?: emptyList()
                }
            } catch (e: Exception) {
                _geocodeResults.value = emptyList()
            } finally {
                _geocodeSearching.value = false
            }
        }
    }

    fun clearGeocodeResults() {
        geocodeJob?.cancel()
        _geocodeResults.value = emptyList()
        _geocodeSearching.value = false
    }

    // ---------- API key test ----------
    fun testTomorrowApiKeyConnection(testKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = repository.tomorrowApi.getForecast(
                    location = "40.7128,-74.0060",
                    apiKey = testKey,
                    units = "metric"
                )
                if (response.isSuccessful) {
                    _showConnectionTestResult.value = Pair(true, "Connection Successful! Key is valid.")
                } else {
                    _showConnectionTestResult.value = Pair(false, "Connection Failed with code: ${response.code()}")
                }
            } catch (e: Exception) {
                _showConnectionTestResult.value = Pair(false, "Network connection error: ${e.localizedMessage}")
            }
        }
    }

    fun clearConnectionTestResult() {
        _showConnectionTestResult.value = null
    }
}

// UI States
sealed interface WeatherUIState {
    data object Loading : WeatherUIState
    data object NoApiKey : WeatherUIState
    data object NoLocations : WeatherUIState
    data class Success(
        val currentTemp: Double,
        val feelsLike: Double,
        val humidity: Double,
        val windSpeed: Double,
        val windDirection: Double,
        val uvIndex: Double,
        val visibility: Double,
        val pressure: Double,
        val weatherCode: Int,
        val lastUpdatedMillis: Long,
        val hourlyForecast: List<HourlyForecastItem>,
        val dailyForecast: List<DailyForecastItem>,
        // Minutely is intentionally not cached — stale minute-by-minute data is useless.
        // Empty for cached/offline state; populated only on successful fresh fetches.
        val minutelyForecast: List<MinutelyForecastItem> = emptyList(),
        val isCached: Boolean
    ) : WeatherUIState
    data class Error(val message: String) : WeatherUIState
}

sealed interface StormUIState {
    data object Loading : StormUIState
    data class NoStorms(
        val lastVerifiedMillis: Long?,
        val isCached: Boolean
    ) : StormUIState
    data class Active(
        val activeStorms: List<StormDetail>,
        val lastVerifiedMillis: Long?,
        val isCached: Boolean
    ) : StormUIState
    data class Error(val message: String) : StormUIState
}

internal fun stormUiStateFrom(result: WeatherRepository.StormSyncResult): StormUIState =
    when (result) {
        is WeatherRepository.StormSyncResult.Fresh -> {
            if (result.storms.isEmpty()) {
                StormUIState.NoStorms(result.lastVerifiedMillis, isCached = false)
            } else {
                StormUIState.Active(result.storms, result.lastVerifiedMillis, isCached = false)
            }
        }
        is WeatherRepository.StormSyncResult.Cached -> {
            if (result.storms.isEmpty()) {
                StormUIState.NoStorms(result.lastVerifiedMillis, isCached = true)
            } else {
                StormUIState.Active(result.storms, result.lastVerifiedMillis, isCached = true)
            }
        }
        is WeatherRepository.StormSyncResult.Unavailable -> {
            StormUIState.Error(result.reason)
        }
    }

sealed interface GpsRequestState {
    data object Idle : GpsRequestState
    data object Loading : GpsRequestState
    data object Success : GpsRequestState
    data class Failed(val reason: String) : GpsRequestState
}
