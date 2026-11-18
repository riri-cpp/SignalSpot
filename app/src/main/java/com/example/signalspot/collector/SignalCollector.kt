package com.example.signalspot.collector

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Looper
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
 * Live signal reader + GPS movement-gated auto-logger (Proposal R1, Risk #3, Risk #4, Risk #5).
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
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var lastSavedLocation by remember { mutableStateOf<Location?>(null) }
    var lastSavedTimeMs by remember { mutableLongStateOf(0L) }
    var sessionSavedCount by remember { mutableIntStateOf(0) }
    var gpsStatusText by remember { mutableStateOf("Searching for GPS...") }

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
    DisposableEffect(hasPermissions, isAutoLogging, lifecycleOwner) {
        if (!hasPermissions) {
            onDispose { }
        } else {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1500L)
                .build()

            fun handleNewLocation(loc: Location) {
                currentLocation = loc
                onLocationUpdated?.invoke(loc.latitude, loc.longitude, loc.accuracy)
                val snapshot = readCurrentSignal(context)
                displayOutput = snapshot.formattedSummary

                if (!isAutoLogging) {
                    gpsStatusText = "Standby: Paused (±${loc.accuracy.toInt()}m)"
                    return
                }

                // Proposal R1 & Risk #3: Skip auto-saving if GPS accuracy is worse than 30 meters
                if (loc.accuracy > MAX_ALLOWED_GPS_ACCURACY_METERS) {
                    gpsStatusText = "Standby: Weak GPS (±${loc.accuracy.toInt()}m > 30m)"
                    return
                }

                val now = System.currentTimeMillis()
                val prevLoc = lastSavedLocation
                val movedMeters = prevLoc?.distanceTo(loc) ?: Float.MAX_VALUE
                val elapsedMs = now - lastSavedTimeMs

                val shouldAutoSave = prevLoc == null ||
                    (movedMeters >= MIN_WALK_DISTANCE_METERS && elapsedMs >= MIN_TIME_BETWEEN_SAVES_MS)

                if (shouldAutoSave) {
                    val reading = snapshot.toSignalReading(
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracy = loc.accuracy
                    )
                    onReadingCaptured?.invoke(reading)
                    lastSavedLocation = loc
                    lastSavedTimeMs = now
                    sessionSavedCount++
                    gpsStatusText =
                        "Auto-Recording: Saved #$sessionSavedCount (±${loc.accuracy.toInt()}m)"
                } else {
                    gpsStatusText =
                        "Auto-Recording: ${movedMeters.toInt()}m / 20m walked (±${loc.accuracy.toInt()}m)"
                }
            }

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    handleNewLocation(loc)
                }
            }

            fun startLocationUpdates() {
                try {
                    fusedClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null && currentLocation == null) {
                            handleNewLocation(loc)
                        }
                    }
                    fusedClient.requestLocationUpdates(
                        locationRequest,
                        callback,
                        Looper.getMainLooper()
                    )
                } catch (_: SecurityException) {
                }
            }

            fun stopLocationUpdates() {
                fusedClient.removeLocationUpdates(callback)
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = displayOutput,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            val queueTag = if (pendingOfflineCount > 0) " · Offline Queued: $pendingOfflineCount" else ""
            Text(
                text = "$gpsStatusText$queueTag",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

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
                            if (loc != null) {
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
                                gpsStatusText = "Waiting for GPS lock to record spot..."
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
