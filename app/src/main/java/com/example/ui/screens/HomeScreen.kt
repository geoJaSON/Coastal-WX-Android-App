package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ActiveAlert
import com.example.data.DailyForecastItem
import com.example.data.DailyValues
import com.example.data.NowcastVerdict
import com.example.data.PreferencesHelper
import com.example.data.SavedLocation
import com.example.data.TideSummary
import com.example.data.TidesService
import com.example.data.UnitFormatter
import com.example.data.WeatherCodeMapper
import com.example.data.computeNowcast
import com.example.data.computePressureTrend
import com.example.data.deriveDailyWeatherCode
import com.example.data.moonPhaseLabel
import com.example.data.uvHealthLabel
import com.example.ui.MainViewModel
import com.example.ui.WeatherUIState
import com.example.ui.components.WeatherConditionIcon
import com.example.ui.components.WeatherTimelineChart
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAlertDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = viewModel.getApplication<android.app.Application>()
    val weatherState by viewModel.weatherState.collectAsState()
    val locations by viewModel.savedLocations.collectAsState()
    val activeId by viewModel.activeLocationId.collectAsState()
    val activeLoc by viewModel.currentLocation.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val activeAlerts by viewModel.activeAlerts.collectAsState()
    val tideSummary by viewModel.tideSummary.collectAsState()

    var isCelsius by remember { mutableStateOf(PreferencesHelper.getTempUnit(context) == "C") }
    var measurementSystem by remember { mutableStateOf(PreferencesHelper.getMeasurementSystem(context)) }
    var showLocationSelector by remember { mutableStateOf(false) }
    var showAdditionalAlerts by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isCelsius = PreferencesHelper.getTempUnit(context) == "C"
        measurementSystem = PreferencesHelper.getMeasurementSystem(context)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val (statusLabel, statusColors) = when {
                        isRefreshing -> "UPDATING" to (
                            MaterialTheme.colorScheme.tertiaryContainer to
                                MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        (weatherState as? WeatherUIState.Success)?.isCached == true -> "CACHED" to (
                            MaterialTheme.colorScheme.secondaryContainer to
                                MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        weatherState is WeatherUIState.Success -> "LIVE" to (
                            MaterialTheme.colorScheme.primaryContainer to
                                MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        weatherState is WeatherUIState.Error -> "OFFLINE" to (
                            MaterialTheme.colorScheme.errorContainer to
                                MaterialTheme.colorScheme.onErrorContainer
                            )
                        weatherState is WeatherUIState.Loading -> "LOADING" to (
                            MaterialTheme.colorScheme.surfaceVariant to
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        else -> "SETUP" to (
                            MaterialTheme.colorScheme.surfaceVariant to
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(32.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { showLocationSelector = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(statusColors.first)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                statusLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColors.second,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Text(
                            text = activeLoc?.name ?: "No Location",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change Location", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshAllData() }) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh Feed")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = weatherState) {
                is WeatherUIState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is WeatherUIState.NoApiKey -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.CloudOff, contentDescription = "No API Key", modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Welcome to Coastal WX", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "A valid Tomorrow.io API Key is required to fetch real-time weather forecasts. Configure your personal key safely inside Settings.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onNavigateToSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Open Settings")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Setup Key in Settings")
                        }
                    }
                }

                is WeatherUIState.NoLocations -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Add a location in Settings or top bar selector to begin.", textAlign = TextAlign.Center)
                    }
                }

                is WeatherUIState.Success -> {
                    val scrollState = rememberScrollState()
                    var expandedDayIndex by remember { mutableStateOf<Int?>(null) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp)
                    ) {
                        AlertSummary(
                            alerts = activeAlerts,
                            expanded = showAdditionalAlerts,
                            onToggleExpanded = { showAdditionalAlerts = !showAdditionalAlerts },
                            onAlertClick = onNavigateToAlertDetail,
                            onDismiss = viewModel::dismissAlertBanner
                        )

                        // Weather cache notice banner
                        if (state.isCached) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.CloudQueue, contentDescription = "Offline Cache", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val timeStr = SimpleDateFormat("h:mm a, MMM dd", Locale.getDefault()).format(Date(state.lastUpdatedMillis))
                                    Text("Viewing offline cached data. Refreshed: $timeStr", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        if (!state.isCached) {
                            val verdict = remember(state.minutelyForecast) {
                                computeNowcast(state.minutelyForecast)
                            }
                            NowcastBanner(verdict)
                        }

                        // Precompute display values
                        val currentFinalVal = UnitFormatter.tempFromC(state.currentTemp, isCelsius)
                        val activeApparent = UnitFormatter.tempFromC(state.feelsLike, isCelsius)
                        val highC = state.dailyForecast.firstOrNull()?.values?.temperatureMax ?: 0.0
                        val lowC = state.dailyForecast.firstOrNull()?.values?.temperatureMin ?: 0.0
                        val highFinal = UnitFormatter.tempFromC(highC, isCelsius)
                        val lowFinal = UnitFormatter.tempFromC(lowC, isCelsius)
                        val windUnit = PreferencesHelper.getWindSpeedUnit(context)
                        val speedValue = UnitFormatter.windFromMs(state.windSpeed, windUnit)
                        val pressureTrend = remember(state.hourlyForecast) {
                            computePressureTrend(state.hourlyForecast)
                        }
                        val pressureFormatted = UnitFormatter.pressure(state.pressure, measurementSystem)
                        val pressureValue = pressureTrend?.let { "$pressureFormatted ${it.symbol}" } ?: pressureFormatted
                        val uvCategory = uvHealthLabel(state.dailyForecast.firstOrNull()?.values?.uvHealthConcernMax)
                        val peakGustMs = state.dailyForecast.firstOrNull()?.values?.windGustMax
                        val peakGustLabel = peakGustMs?.let { UnitFormatter.windFromMs(it, windUnit) } ?: "—"
                        val ceilingKm = state.hourlyForecast.firstOrNull()?.values?.cloudCeiling
                        val ceilingLabel = ceilingKm?.let {
                            "Ceiling " + UnitFormatter.distanceKmFractional(it, measurementSystem)
                        }

                        HomeSectionHeader(
                            title = "NOW",
                            subtitle = "Current conditions",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(
                                modifier = Modifier.background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                                            MaterialTheme.colorScheme.surface
                                        )
                                    )
                                )
                            ) {
                                // Two-column hero: current conditions left, essential metrics right.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                            // LEFT: primary conditions (temperature + condition icon, emphasized)
                            Column(
                                modifier = Modifier
                                    .weight(0.56f)
                                    .padding(end = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                WeatherConditionIcon(
                                    weatherCode = state.weatherCode,
                                    modifier = Modifier.size(72.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        "${currentFinalVal.toInt()}°",
                                        fontSize = 80.sp,
                                        fontWeight = FontWeight.Light,
                                        letterSpacing = (-2).sp,
                                        lineHeight = 80.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        if (isCelsius) "C" else "F",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 10.dp, start = 2.dp)
                                    )
                                }
                                Text(
                                    WeatherCodeMapper.getWeatherDescription(state.weatherCode),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Text(
                                    "H: ${highFinal.toInt()}°  •  L: ${lowFinal.toInt()}°",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        "Feels like ${activeApparent.toInt()}°",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                                    // RIGHT: essential, immediately actionable conditions.
                                    Column(
                                        modifier = Modifier.weight(0.44f),
                                        verticalArrangement = Arrangement.spacedBy(13.dp)
                                    ) {
                                        CompactMetricRow(Icons.Filled.WaterDrop, "HUMIDITY", "${state.humidity.toInt()}%")
                                        CompactMetricRow(Icons.Filled.Air, "WIND", speedValue)
                                        CompactMetricRow(Icons.Filled.Speed, "BAROMETER", pressureValue, pressureTrend?.label)
                                        CompactMetricRow(
                                            Icons.Filled.Visibility,
                                            "VISIBILITY",
                                            UnitFormatter.distanceKm(state.visibility, measurementSystem),
                                            ceilingLabel
                                        )
                                    }
                                }
                            }
                        }

                        // Supporting values are grouped by time horizon below the hero.
                        val todayRainMm = remember(state.hourlyForecast) {
                            state.hourlyForecast.take(24).sumOf { it.values.rainIntensity ?: 0.0 }
                        }
                        val rainTodayLabel = UnitFormatter.rainFromMm(todayRainMm, measurementSystem)
                        val currentHour = state.hourlyForecast.firstOrNull()?.values
                        val dewC = currentHour?.dewPoint
                        val dewStr = dewC?.let { "${UnitFormatter.tempFromC(it, isCelsius).toInt()}°" } ?: "—"
                        val today = state.dailyForecast.firstOrNull()?.values

                        HomeSectionHeader(
                            title = "UP NEXT",
                            subtitle = "Coastal timing and the next several hours",
                            modifier = Modifier.padding(top = 24.dp)
                        )
                        CoastalAndSkyCard(
                            summary = tideSummary,
                            today = today,
                            measurementSystem = measurementSystem
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            WeatherTimelineChart(
                                hourlyList = state.hourlyForecast,
                                isCelsius = isCelsius,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }

                        HomeSectionHeader(
                            title = "TODAY",
                            subtitle = "Daily totals and exposure",
                            modifier = Modifier.padding(top = 24.dp)
                        )
                        TodayDetailsGrid(
                            rainLabel = rainTodayLabel,
                            peakGustLabel = peakGustLabel,
                            dewPointLabel = dewStr,
                            uvLabel = state.uvIndex.toInt().toString(),
                            uvCategory = uvCategory.takeIf { it != "—" }
                        )

                        HomeSectionHeader(
                            title = "NEXT 5 DAYS",
                            subtitle = "Tap a day for full details",
                            modifier = Modifier.padding(top = 24.dp)
                        )
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 32.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                state.dailyForecast.forEachIndexed { index, day ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }

                                    val isExpanded = expandedDayIndex == index
                                    val dateStr = try {
                                        val parseFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                                            timeZone = TimeZone.getTimeZone("UTC")
                                        }
                                        val dayDate = parseFormat.parse(day.time)
                                        SimpleDateFormat("EEEE", Locale.getDefault()).format(dayDate ?: Date())
                                    } catch (e: Exception) {
                                        "Outlook"
                                    }

                                    val tMin = day.values.temperatureMin ?: 0.0
                                    val tMax = day.values.temperatureMax ?: 0.0
                                    val minValFinal = UnitFormatter.tempFromC(tMin, isCelsius)
                                    val maxValFinal = UnitFormatter.tempFromC(tMax, isCelsius)
                                    val precipPct = day.values.precipitationProbabilityAvg
                                        ?: day.values.precipitationProbability
                                        ?: 0.0

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                expandedDayIndex = if (isExpanded) null else index
                                            }
                                            .padding(vertical = 10.dp, horizontal = 2.dp)
                                    ) {
                                        // Day summary row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (index == 0) "Today" else dateStr,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                modifier = Modifier.width(88.dp)
                                            )
                                            WeatherConditionIcon(
                                                weatherCode = deriveDailyWeatherCode(day.values),
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Text(
                                                text = "${precipPct.toInt()}%",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF0284C7),
                                                modifier = Modifier
                                                    .padding(horizontal = 8.dp)
                                                    .width(32.dp),
                                                textAlign = TextAlign.End
                                            )
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text(
                                                text = "${minValFinal.toInt()}°",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(32.dp),
                                                textAlign = TextAlign.End
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .padding(horizontal = 8.dp)
                                                    .height(4.dp)
                                                    .width(40.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(MaterialTheme.colorScheme.outlineVariant)
                                            )
                                            Text(
                                                text = "${maxValFinal.toInt()}°",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.width(32.dp),
                                                textAlign = TextAlign.End
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // Expandable detail panel
                                        AnimatedVisibility(
                                            visible = isExpanded,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut()
                                        ) {
                                            DayDetailPanel(
                                                day = day,
                                                isCelsius = isCelsius,
                                                measurementSystem = measurementSystem,
                                                windUnit = windUnit
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                is WeatherUIState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = "Error Occurred", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("An Error Occurred", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { viewModel.refreshAllData() }) {
                            Text("Retry Connection")
                        }
                    }
                }
            }

            // Floating Location Selector Modal
            if (showLocationSelector) {
                AlertDialog(
                    onDismissRequest = { showLocationSelector = false },
                    title = { Text("Select Station Location", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            locations.forEach { loc ->
                                val active = loc.id == activeId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable {
                                            viewModel.selectActiveLocation(loc.id)
                                            showLocationSelector = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (loc.isGPS) Icons.Filled.MyLocation else Icons.Filled.LocationCity,
                                        contentDescription = "Station",
                                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        loc.name,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Filled.Add, contentDescription = "Manage")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Manage Stations in Settings")
                        }
                    }
                )
            }
        }
    }
}

private fun formatTimeUtc(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val d = parser.parse(iso) ?: return null
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(d)
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun AlertSummary(
    alerts: List<ActiveAlert>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAlertClick: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    if (alerts.isEmpty()) return
    val orderedAlerts = remember(alerts) {
        alerts.sortedByDescending { alertSeverityWeight(it.severity) }
    }
    val primaryAlert = orderedAlerts.first()
    val additionalAlerts = orderedAlerts.drop(1)

    Column(
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAlertClick(primaryAlert.id) },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Active Warning",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        primaryAlert.title,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        primaryAlert.headline ?: primaryAlert.instruction
                            ?: primaryAlert.areaDesc ?: primaryAlert.description,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.82f),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${primaryAlert.severity.uppercase()}  ·  VIEW DETAILS",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
                IconButton(onClick = { onDismiss(primaryAlert.id) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss alert banner",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (additionalAlerts.isNotEmpty()) {
            TextButton(
                onClick = onToggleExpanded,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    if (expanded) "HIDE ADDITIONAL ALERTS"
                    else "${additionalAlerts.size} MORE ALERT${if (additionalAlerts.size == 1) "" else "S"}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    additionalAlerts.forEach { alert ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onAlertClick(alert.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        alert.title,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        alert.severity.uppercase(),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                IconButton(onClick = { onDismiss(alert.id) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Dismiss alert banner",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun alertSeverityWeight(severity: String): Int = when (severity.lowercase()) {
    "extreme" -> 4
    "severe" -> 3
    "moderate" -> 2
    "minor" -> 1
    else -> 0
}

@Composable
private fun HomeSectionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            subtitle,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Compact metric row for the right hero column.
@Composable
private fun CompactMetricRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    subtitle: String? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.4.sp
            )
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CoastalAndSkyCard(
    summary: TideSummary?,
    today: DailyValues?,
    measurementSystem: String
) {
    val nextTide = summary?.nextEvent()
    val sunrise = formatTimeUtc(today?.sunriseTime)
    val sunset = formatTimeUtc(today?.sunsetTime)
    val moonLabel = moonPhaseLabel(today?.moonPhase)
    val moonrise = formatTimeUtc(today?.moonriseTime)
    val moonset = formatTimeUtc(today?.moonsetTime)
    val hasSkyData = sunrise != null || sunset != null || moonLabel != "—" ||
        moonrise != null || moonset != null

    if (nextTide == null && !hasSkyData) return

    val tideTime = nextTide?.let { tide ->
        TidesService.parsePredictionTime(tide.time)?.let { timestamp ->
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
        } ?: tide.time
    }
    val tideHeight = nextTide?.value?.toDoubleOrNull()
        ?.let { UnitFormatter.tideFromFeet(it, measurementSystem) }
        ?: nextTide?.value
    val tideType = nextTide?.let { if (it.type == "H") "High Tide" else "Low Tide" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Waves,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "COASTAL & SKY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.7.sp
                    )
                    Text(
                        summary?.stationName ?: "Sun and moon timing",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (nextTide != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "NEXT TIDE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                "$tideType · $tideTime",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (!tideHeight.isNullOrBlank()) {
                            Text(
                                tideHeight,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            if (hasSkyData) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.WbTwilight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "DAYLIGHT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 0.4.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            "Rise ${sunrise ?: "—"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Set ${sunset ?: "—"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .width(1.dp)
                            .height(54.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.NightlightRound,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "MOON",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 0.4.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            moonLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val moonTiming = listOfNotNull(
                            moonrise?.let { "Rise $it" },
                            moonset?.let { "Set $it" }
                        ).joinToString(" · ")
                        if (moonTiming.isNotBlank()) {
                            Text(
                                moonTiming,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayDetailsGrid(
    rainLabel: String,
    peakGustLabel: String,
    dewPointLabel: String,
    uvLabel: String,
    uvCategory: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MeteorologyIndicatorCard(
            title = "NEXT 24H RAIN",
            value = rainLabel,
            icon = Icons.Filled.WaterDrop,
            modifier = Modifier.weight(1f).heightIn(min = 92.dp),
            subtitle = "Expected total"
        )
        MeteorologyIndicatorCard(
            title = "TODAY'S PEAK",
            value = peakGustLabel,
            icon = Icons.Filled.Tsunami,
            modifier = Modifier.weight(1f).heightIn(min = 92.dp),
            subtitle = "Wind gust"
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MeteorologyIndicatorCard(
            title = "DEW POINT",
            value = dewPointLabel,
            icon = Icons.Filled.Opacity,
            modifier = Modifier.weight(1f).heightIn(min = 92.dp)
        )
        MeteorologyIndicatorCard(
            title = "UV INDEX",
            value = uvLabel,
            icon = Icons.Filled.WbSunny,
            modifier = Modifier.weight(1f).heightIn(min = 92.dp),
            subtitle = uvCategory
        )
    }
}

// ── Expandable day detail panel ──
@Composable
private fun DayDetailPanel(
    day: DailyForecastItem,
    isCelsius: Boolean,
    measurementSystem: String,
    windUnit: String
) {
    val v = day.values

    fun fmtTemp(c: Double?) = c?.let {
        "${UnitFormatter.tempFromC(it, isCelsius).toInt()}°"
    } ?: "—"

    fun fmtWind(ms: Double?) = ms?.let { UnitFormatter.windFromMs(it, windUnit) } ?: "—"

    val feelsMin = fmtTemp(v.temperatureApparentMin)
    val feelsMax = fmtTemp(v.temperatureApparentMax)
    val feelsRange = if (feelsMin != "—" && feelsMax != "—") "$feelsMin – $feelsMax" else feelsMin

    val precipAvg = (v.precipitationProbabilityAvg ?: v.precipitationProbability ?: 0.0).toInt()
    val precipMax = (v.precipitationProbabilityMax ?: precipAvg.toDouble()).toInt()
    val precipStr = if (precipMax > precipAvg) "$precipAvg% avg · $precipMax% max" else "$precipAvg%"

    val rainAccStr = v.rainAccumulationSum
        ?.let { UnitFormatter.rainFromMm(it, measurementSystem) }
        ?: "—"

    val windAvg = fmtWind(v.windSpeedAvg)
    val windMax = fmtWind(v.windSpeedMax)
    val windRangeStr = if (windAvg != "—" && windMax != "—" && windAvg != windMax) "$windAvg – $windMax" else windAvg

    val gustStr = fmtWind(v.windGustMax)

    val humidStr = v.humidityAvg?.let { "${it.toInt()}%" } ?: "—"

    val uvStr = v.uvIndexMax?.let {
        val cat = uvHealthLabel(v.uvHealthConcernMax)
        if (cat != "—") "${it.toInt()} · $cat" else "${it.toInt()}"
    } ?: "—"

    val cloudStr = (v.cloudCoverAvg ?: v.cloudCover)?.let { "${it.toInt()}%" } ?: "—"

    val sunriseStr = formatTimeUtc(v.sunriseTime) ?: "—"
    val sunsetStr = formatTimeUtc(v.sunsetTime) ?: "—"

    val moonLabel = moonPhaseLabel(v.moonPhase)
    val moonriseStr = formatTimeUtc(v.moonriseTime)
    val moonsetStr = formatTimeUtc(v.moonsetTime)
    val moonSub = listOfNotNull(
        moonriseStr?.let { "Rise $it" },
        moonsetStr?.let { "Set $it" }
    ).joinToString(" · ").takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailItem(Icons.Filled.DeviceThermostat, "FEELS LIKE", feelsRange, null, Modifier.weight(1f))
            DetailItem(Icons.Filled.Umbrella, "PRECIP CHANCE", precipStr, null, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailItem(Icons.Filled.WaterDrop, "RAIN ACC.", rainAccStr, null, Modifier.weight(1f))
            DetailItem(Icons.Filled.Tsunami, "PEAK GUST", gustStr, null, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailItem(Icons.Filled.Air, "WIND RANGE", windRangeStr, null, Modifier.weight(1f))
            DetailItem(Icons.Filled.WaterDrop, "HUMIDITY", humidStr, null, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailItem(Icons.Filled.WbSunny, "UV INDEX", uvStr, null, Modifier.weight(1f))
            DetailItem(Icons.Filled.Cloud, "CLOUD COVER", cloudStr, null, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailItem(Icons.Filled.WbTwilight, "SUNRISE", sunriseStr, null, Modifier.weight(1f))
            DetailItem(Icons.Filled.Bedtime, "SUNSET", sunsetStr, null, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailItem(Icons.Filled.NightlightRound, "MOON PHASE", moonLabel, moonSub, Modifier.weight(1f))
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun DetailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(13.dp)
                .padding(top = 1.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Column {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.3.sp
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MeteorologyIndicatorCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = value,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NowcastBanner(verdict: NowcastVerdict) {
    val (text, accent) = when (verdict) {
        is NowcastVerdict.Starting -> {
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(Date(verdict.startMillis))
            "${verdict.kind.label} starting in ${verdict.minutesUntilStart} min ($timeStr)" to true
        }
        is NowcastVerdict.Stopping -> {
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(Date(verdict.endMillis))
            "${verdict.kind.label} stopping at $timeStr" to true
        }
        is NowcastVerdict.Continuing -> "${verdict.kind.label} continuing past next hour" to true
        NowcastVerdict.Clear -> return
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        color = if (accent) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Umbrella,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}
