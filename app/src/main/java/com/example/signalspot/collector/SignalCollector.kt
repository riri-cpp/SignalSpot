package com.example.signalspot.collector

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellSignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.signalspot.data.SignalReading
import com.example.signalspot.data.gridKeyFor
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.UUID

private const val MIN_WALK_DISTANCE_METERS = 20f
private const val MIN_TIME_BETWEEN_SAVES_MS = 10_000L
private const val MAX_ALLOWED_GPS_ACCURACY_METERS = 30f

// Anti-teleport thresholds:
// Rejects location fixes older than 10 seconds
private const val MAX_LOCATION_AGE_NANOS = 10_000_000_000L
// Maximum plausible walking/campus movement jump before requiring multi-fix confirmation
private const val TELEPORT_DISTANCE_THRESHOLD_METERS = 60f
private const val MAX_PLAUSIBLE_SPEED_MPS = 12.0 // ~43 km/h
private const val REQUIRED_CONSECUTIVE_FIXES_FOR_LARGE_JUMP = 3

// Approximate BSU Alangilan center & campus geofence radius (optional campus guard)
private const val BSU_CAMPUS_LAT = 13.7847
private const val BSU_CAMPUS_LON = 121.0735
private const val BSU_CAMPUS_RADIUS_METERS = 650f

/**
 * Holds the raw snapshot of cellular and Wi-Fi signal metrics for UI display
 * and conversion into SignalReading.
 */
data class RawSignalSnapshot(
    val carrier: String,
    val networkType: String,
    val dbm: Int,
    val level: Int,
    val wifiDbm: Int?,
    val formattedSummary: String
) {
    /**
     * Converts this snapshot into the shared SignalReading contract from data/SignalReading.kt.
     */
    fun toSignalReading(
        lat: Double = 0.0,
        lng: Double = 0.0,
        accuracy: Float = 0f
    ): SignalReading {
        return SignalReading(
            id = UUID.randomUUID().toString(),
            lat = lat,
            lng = lng,
            gridKey = gridKeyFor(lat, lng),
            accuracy = accuracy,
            carrier = carrier,
            networkType = networkType,
            dbm = dbm,
            level = level,
            wifiDbm = wifiDbm,
            timestamp = System.currentTimeMillis()
        )
    }
}

/**
 * Live signal reader + GPS movement-gated auto-logger with Anti-Teleport & Hardware GNSS filtering.
 */
@SuppressLint("MissingPermission")
@Composable
fun SignalCollectorScreen(
    modifier: Modifier = Modifier,
    pendingOfflineCount: Int = 0,
    onReadingCaptured: ((SignalReading) -> Unit)? = null,
    onLocationUpdated: ((lat: Double, lng: Double, accuracy: Float) -> Unit)? = null
) {
    val context = LocalContext.current

    val requiredPermissions = remember {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
    }

    var hasPermissions by remember {
        mutableStateOf(checkAllPermissionsGranted(context, requiredPermissions))
    }

    var displayOutput by remember {
        mutableStateOf("Waiting for permissions...")
    }

    var isAutoLogging by remember { mutableStateOf(true) }
    // When enabled on campus, prefers pure satellite GPS and blocks out-of-campus cell/hotspot teleports
    var preferPureSatelliteGps by remember { mutableStateOf(true) }
    var lockToBsuCampusArea by remember { mutableStateOf(false) }

    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var lastSavedLocation by remember { mutableStateOf<Location?>(null) }
    var lastSavedTimeMs by remember { mutableLongStateOf(0L) }
    var lastHardwareGpsFixTimeMs by remember { mutableLongStateOf(0L) }
    var candidateJumpLocation by remember { mutableStateOf<Location?>(null) }
    var candidateJumpCount by remember { mutableIntStateOf(0) }
    var sessionSavedCount by remember { mutableIntStateOf(0) }
    var gpsStatusText by remember { mutableStateOf("Searching for GPS satellites...") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.all { it }
        hasPermissions = allGranted
        if (allGranted) {
            displayOutput = readCurrentSignal(context).formattedSummary
        } else {
            displayOutput = "Permissions denied. Location and Phone permissions are required."
        }
    }

    LaunchedEffect(Unit) {
        if (hasPermissions) {
            displayOutput = readCurrentSignal(context).formattedSummary
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Foreground-only GPS listener: starts on RESUME and stops immediately on PAUSE / close (Risk #5)
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    DisposableEffect(
        hasPermissions,
        isAutoLogging,
        preferPureSatelliteGps,
        lockToBsuCampusArea,
        lifecycleOwner
    ) {
        if (!hasPermissions) {
            onDispose { }
        } else {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2500L)
                .setMinUpdateIntervalMillis(1500L)
                .setMaxUpdateAgeMillis(5000L)
                .setWaitForAccurateLocation(true)
                .build()

            fun handleNewLocation(loc: Location, isFromHardwareGps: Boolean) {
                val snapshot = readCurrentSignal(context)
                displayOutput = snapshot.formattedSummary

                // 1. Reject stale cached locations (older than 10 seconds)
                val ageNanos = SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos
                if (ageNanos > MAX_LOCATION_AGE_NANOS) {
                    return
                }

                // 2. Reject Mock locations
                @Suppress("DEPRECATION")
                if (loc.isFromMockProvider) {
                    gpsStatusText = "Rejected mock location fix"
                    return
                }

                // 3. If hardware satellite GPS is actively providing fixes (within last 8s),
                // ignore Fused network/Wi-Fi hotspot locations that cause campus teleportation!
                val nowMs = System.currentTimeMillis()
                if (isFromHardwareGps) {
                    lastHardwareGpsFixTimeMs = nowMs
                } else if (preferPureSatelliteGps && (nowMs - lastHardwareGpsFixTimeMs) < 8_000L) {
                    return
                }

                // 4. Optional BSU Campus Geofence Guard:
                // Blocks cell-tower or student-hotspot database teleports outside BSU Alangilan
                if (lockToBsuCampusArea) {
                    val distFromBsu = FloatArray(1)
                    Location.distanceBetween(
                        BSU_CAMPUS_LAT,
                        BSU_CAMPUS_LON,
                        loc.latitude,
                        loc.longitude,
                        distFromBsu
                    )
                    if (distFromBsu[0] > BSU_CAMPUS_RADIUS_METERS) {
                        gpsStatusText =
                            "Blocked teleport (${distFromBsu[0].toInt()}m from BSU)"
                        return
                    }
                }

                // 5. CRITICAL FIX: Check 30m accuracy BEFORE updating currentLocation or moving map!
                // Previously, cell-tower fixes (accuracy 300m-1500m) updated currentLocation first,
                // which teleported the blue dot to the cell tower outside campus.
                if (loc.accuracy <= 0f || loc.accuracy > MAX_ALLOWED_GPS_ACCURACY_METERS) {
                    val keepLabel = if (currentLocation != null) " · Holding last good spot" else ""
                    gpsStatusText =
                        "Standby: Weak GPS (±${loc.accuracy.toInt()}m > 30m)$keepLabel"
                    return
                }

                // 6. Anti-Teleport / Impossible Velocity Spike Filter:
                // If a single fix suddenly jumps > 60m at > 12 m/s (e.g. nearby mobile hotspot MAC
                // registered in another town), reject it unless 3 consecutive accurate fixes confirm it.
                val prevVerified = currentLocation
                if (prevVerified != null) {
                    val jumpDist = prevVerified.distanceTo(loc)
                    val dtSec = ((loc.elapsedRealtimeNanos - prevVerified.elapsedRealtimeNanos) /
                        1_000_000_000.0).coerceAtLeast(0.5)
                    val speedMps = jumpDist / dtSec

                    if (jumpDist > TELEPORT_DISTANCE_THRESHOLD_METERS &&
                        speedMps > MAX_PLAUSIBLE_SPEED_MPS
                    ) {
                        val candidate = candidateJumpLocation
                        if (candidate != null && candidate.distanceTo(loc) <= 30f) {
                            candidateJumpCount++
                        } else {
                            candidateJumpLocation = loc
                            candidateJumpCount = 1
                        }

                        if (candidateJumpCount < REQUIRED_CONSECUTIVE_FIXES_FOR_LARGE_JUMP) {
                            gpsStatusText =
                                "Filtered GPS spike (${jumpDist.toInt()}m jump blocked)"
                            return
                        }
                        // Confirmed new location after 3 consistent fixes
                        candidateJumpLocation = null
                        candidateJumpCount = 0
                    } else {
                        candidateJumpLocation = null
                        candidateJumpCount = 0
                    }
                }

                // Verified, accurate, non-teleported location!
                currentLocation = loc
                onLocationUpdated?.invoke(loc.latitude, loc.longitude, loc.accuracy)
                val sourceTag = if (isFromHardwareGps) "GNSS" else "Fused"

                if (!isAutoLogging) {
                    gpsStatusText = "Standby: Paused ($sourceTag ±${loc.accuracy.toInt()}m)"
                    return
                }

                val prevSaved = lastSavedLocation
                val movedMeters = prevSaved?.distanceTo(loc) ?: Float.MAX_VALUE
                val elapsedMs = nowMs - lastSavedTimeMs

                val shouldAutoSave = prevSaved == null ||
                    (movedMeters >= MIN_WALK_DISTANCE_METERS && elapsedMs >= MIN_TIME_BETWEEN_SAVES_MS)

                if (shouldAutoSave) {
                    val reading = snapshot.toSignalReading(
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracy = loc.accuracy
                    )
                    onReadingCaptured?.invoke(reading)
                    lastSavedLocation = loc
                    lastSavedTimeMs = nowMs
                    sessionSavedCount++
                    gpsStatusText =
                        "Auto-Recording ($sourceTag): Saved #$sessionSavedCount (±${loc.accuracy.toInt()}m)"
                } else {
                    gpsStatusText =
                        "Auto-Recording ($sourceTag): ${movedMeters.toInt()}m / 20m walked (±${loc.accuracy.toInt()}m)"
                }
            }

            val fusedCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    handleNewLocation(loc, isFromHardwareGps = false)
                }
            }

            val hardwareGpsListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    handleNewLocation(location, isFromHardwareGps = true)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            fun startLocationUpdates() {
                try {
                    // Request direct hardware satellite GPS (immune to cell-tower & student hotspot teleports)
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            2000L,
                            0f,
                            hardwareGpsListener,
                            Looper.getMainLooper()
                        )
                    }
                    // Also run Fused High-Accuracy as backup when satellites are still locking
                    fusedClient.requestLocationUpdates(
                        locationRequest,
                        fusedCallback,
                        Looper.getMainLooper()
                    )
                } catch (_: SecurityException) {
                }
            }

            fun stopLocationUpdates() {
                fusedClient.removeLocationUpdates(fusedCallback)
                try {
                    locationManager.removeUpdates(hardwareGpsListener)
                } catch (_: SecurityException) {
                }
            }

            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> startLocationUpdates()
                    Lifecycle.Event.ON_PAUSE -> stopLocationUpdates()
                    else -> {}
                }
            }

            if (lifecycleOwner != null) {
                lifecycleOwner.lifecycle.addObserver(observer)
            } else {
                startLocationUpdates()
            }

            onDispose {
                lifecycleOwner?.lifecycle?.removeObserver(observer)
                stopLocationUpdates()
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = displayOutput,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(2.dp))

            val queueTag = if (pendingOfflineCount > 0) " · Offline Queued: $pendingOfflineCount" else ""
            Text(
                text = "$gpsStatusText$queueTag",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Anti-Teleport Protection Chips
            if (hasPermissions) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = preferPureSatelliteGps,
                        onClick = { preferPureSatelliteGps = !preferPureSatelliteGps },
                        label = { Text("Pure GNSS Priority", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = lockToBsuCampusArea,
                        onClick = { lockToBsuCampusArea = !lockToBsuCampusArea },
                        label = { Text("BSU Campus Lock", style = MaterialTheme.typography.labelSmall) }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val grantedNow = checkAllPermissionsGranted(context, requiredPermissions)
                        hasPermissions = grantedNow
                        if (grantedNow) {
                            displayOutput = readCurrentSignal(context).formattedSummary
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    }
                ) {
                    Text(text = if (hasPermissions) "Refresh" else "Grant Permissions")
                }

                if (hasPermissions) {
                    OutlinedButton(
                        onClick = {
                            isAutoLogging = !isAutoLogging
                            gpsStatusText = if (isAutoLogging) {
                                "Auto-Recording resumed"
                            } else {
                                "Standby: Paused"
                            }
                        }
                    ) {
                        Text(text = if (isAutoLogging) "Pause" else "Resume")
                    }

                    Button(
                        onClick = {
                            val loc = currentLocation
                            val snapshot = readCurrentSignal(context)
                            displayOutput = snapshot.formattedSummary
                            if (loc != null && loc.accuracy <= MAX_ALLOWED_GPS_ACCURACY_METERS) {
                                val reading = snapshot.toSignalReading(
                                    lat = loc.latitude,
                                    lng = loc.longitude,
                                    accuracy = loc.accuracy
                                )
                                onReadingCaptured?.invoke(reading)
                                lastSavedLocation = loc
                                lastSavedTimeMs = System.currentTimeMillis()
                                sessionSavedCount++
                                gpsStatusText =
                                    "Recorded Spot #$sessionSavedCount (±${loc.accuracy.toInt()}m)"
                            } else {
                                gpsStatusText =
                                    "Wait for accurate GPS (≤30m) before recording..."
                            }
                        }
                    ) {
                        Text(text = "Record Spot")
                    }
                }
            }
        }
    }
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is LifecycleOwner) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun checkAllPermissionsGranted(
    context: Context,
    permissions: Array<String>
): Boolean {
    return permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Reads the current cellular signal from the default mobile data SIM (API 29 safe)
 * and the current Wi-Fi RSSI dBm.
 */
@SuppressLint("MissingPermission")
fun readCurrentSignal(context: Context): RawSignalSnapshot {
    val appContext = context.applicationContext
    val baseTelephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // CRITICAL DUAL-SIM FIX FOR API 29:
    // Reads from the active mobile data SIM without using API 30's getActiveDataSubscriptionId()
    val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
    val telephonyManager = if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        baseTelephonyManager.createForSubscriptionId(dataSubId)
    } else {
        baseTelephonyManager
    }

    // 1. Carrier Name (normalized to Globe / Smart / DITO when recognized)
    val rawCarrier = telephonyManager.networkOperatorName?.trim().orEmpty()
    val simCarrier = telephonyManager.simOperatorName?.trim().orEmpty()
    val chosenCarrier = when {
        rawCarrier.isNotEmpty() -> rawCarrier
        simCarrier.isNotEmpty() -> simCarrier
        else -> "Unknown"
    }
    val carrier = normalizeCarrierName(chosenCarrier)

    // 2. Network Type (2G / 3G / 4G / 5G)
    val networkType = try {
        mapNetworkTypeToGeneration(telephonyManager.dataNetworkType)
    } catch (_: SecurityException) {
        "Unknown"
    }

    // 3. Signal Strength (0-4 bars and exact dBm)
    val signalStrength = telephonyManager.signalStrength
    val level = (signalStrength?.level ?: 0).coerceIn(0, 4)

    val cellSignalList: List<CellSignalStrength> =
        signalStrength?.cellSignalStrengths ?: emptyList()
    val rawDbm = cellSignalList.firstOrNull {
        it.dbm != CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN && it.dbm != Int.MAX_VALUE
    }?.dbm ?: Int.MAX_VALUE

    // INVALID DBM FALLBACK:
    // If exact dBm returns 2147483647 (Int.MAX_VALUE) or is invalid, fall back to "—" for dBm
    val isDbmValid = rawDbm != Int.MAX_VALUE && rawDbm < 0
    val dbmDisplay = if (isDbmValid) "$rawDbm dBm" else "— dBm"
    val cleanDbmValue = if (isDbmValid) rawDbm else 0

    // 4. Wi-Fi Signal Reading
    val wifiDbm = readConnectedWifiDbm(appContext)
    val wifiDisplay = if (wifiDbm != null) "$wifiDbm dBm" else "Not connected"

    // Target output format: Smart · 4G · -92 dBm · 3 bars · Wi-Fi: -61 dBm
    val formattedSummary = "$carrier · $networkType · $dbmDisplay · $level bars · Wi-Fi: $wifiDisplay"

    return RawSignalSnapshot(
        carrier = carrier,
        networkType = networkType,
        dbm = cleanDbmValue,
        level = level,
        wifiDbm = wifiDbm,
        formattedSummary = formattedSummary
    )
}

private fun normalizeCarrierName(raw: String): String {
    val lower = raw.lowercase(Locale.ROOT)
    return when {
        "smart" in lower || "tnt" in lower || "talk 'n text" in lower || "sun" in lower -> "Smart"
        "globe" in lower || "tm" == lower || "touch mobile" in lower || "islacom" in lower -> "Globe"
        "dito" in lower -> "DITO"
        else -> raw
    }
}

@Suppress("DEPRECATION")
private fun mapNetworkTypeToGeneration(networkType: Int): String {
    return when (networkType) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"

        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"

        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"

        TelephonyManager.NETWORK_TYPE_NR -> "5G"

        else -> "Unknown"
    }
}

@Suppress("DEPRECATION")
private fun readConnectedWifiDbm(context: Context): Int? {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = connectivityManager.activeNetwork ?: return null
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null

    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return null
    }

    val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val wifiInfo = wifiManager.connectionInfo ?: return null
    val rssi = wifiInfo.rssi

    return if (rssi in -126..-1) rssi else null
}
