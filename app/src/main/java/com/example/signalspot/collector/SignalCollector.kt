package com.example.signalspot.collector

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.signalspot.R
import com.example.signalspot.data.SignalReading
import com.example.signalspot.data.gridKeyFor
import com.example.signalspot.ui.theme.SignalFairAmber
import com.example.signalspot.ui.theme.SignalStrongGreen
import com.example.signalspot.ui.theme.SignalWeakRed
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.UUID

private const val MIN_WALK_DISTANCE_METERS = 20f
private const val MIN_TIME_BETWEEN_SAVES_MS = 10_000L
private const val MAX_ALLOWED_SAVE_ACCURACY_METERS = 30f
private const val MAX_DISPLAY_DOT_ACCURACY_METERS = 150f

// Anti-teleport thresholds (30s age window to safely handle slower satellite cold-starts)
private const val MAX_LOCATION_AGE_MS = 30_000L
private const val TELEPORT_DISTANCE_THRESHOLD_METERS = 100f
private const val MAX_PLAUSIBLE_SPEED_MPS = 20.0 // ~72 km/h
private const val REQUIRED_CONSECUTIVE_FIXES_FOR_LARGE_JUMP = 2

// Approximate BSU Alangilan center & campus geofence radius
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
 * Visual 4-bar cellular signal strength indicator matching the SignalSpot mockup aesthetic.
 */
@Composable
fun SignalStrengthBars(
    level: Int,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val clamped = level.coerceIn(0, 4)
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val heights = listOf(8.dp, 13.dp, 18.dp, 24.dp)
        heights.forEachIndexed { index, barHeight ->
            Box(
                modifier = Modifier
                    .width(5.5.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(if (index < clamped) activeColor else inactiveColor)
            )
        }
    }
}

/**
 * Sleek floating bottom Live Signal & Auto-Logger card styled after the SignalSpot mockups.
 */
@SuppressLint("MissingPermission")
@Composable
fun SignalCollectorBottomCard(
    modifier: Modifier = Modifier,
    isCardVisible: Boolean = true,
    showOptions: Boolean = false,
    onToggleShowOptions: ((Boolean) -> Unit)? = null,
    pendingOfflineCount: Int = 0,
    locateRequestCounter: Int = 0,
    preferPureSatelliteGps: Boolean = true,
    lockToBsuCampusArea: Boolean = false,
    onTogglePureGnss: ((Boolean) -> Unit)? = null,
    onToggleBsuCampusLock: ((Boolean) -> Unit)? = null,
    onReadingCaptured: ((SignalReading) -> Unit)? = null,
    onLocationUpdated: ((lat: Double, lng: Double, accuracy: Float) -> Unit)? = null,
    onSendTestReading: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val requiredPermissions = remember {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
    }

    var hasFineLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCoarseLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasPhonePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val hasAnyLocation = hasFineLocation || hasCoarseLocation

    var latestSnapshot by remember { mutableStateOf<RawSignalSnapshot?>(null) }
    var isAutoLogging by remember { mutableStateOf(true) }
    var localShowOptions by remember { mutableStateOf(showOptions) }
    val effectiveShowOptions = if (onToggleShowOptions != null) showOptions else localShowOptions
    val setShowOptions: (Boolean) -> Unit = { next ->
        if (onToggleShowOptions != null) onToggleShowOptions(next) else localShowOptions = next
    }

    // Persist last saved GPS anchor so reopening while standing still doesn't spam duplicates
    val savePrefs = remember(context) {
        context.applicationContext.getSharedPreferences("signalspot_last_save", Context.MODE_PRIVATE)
    }

    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var lastVerifiedSaveLocation by remember {
        mutableStateOf(
            if (savePrefs.contains("lat") && savePrefs.contains("lng")) {
                Location("saved_anchor").apply {
                    latitude = Double.fromBits(savePrefs.getLong("lat", 0L))
                    longitude = Double.fromBits(savePrefs.getLong("lng", 0L))
                }
            } else {
                null
            }
        )
    }
    var lastSavedTimeMs by remember {
        mutableLongStateOf(savePrefs.getLong("time_ms", 0L))
    }

    fun persistLastSavedAnchor(loc: Location, timeMs: Long) {
        lastVerifiedSaveLocation = loc
        lastSavedTimeMs = timeMs
        savePrefs.edit()
            .putLong("lat", loc.latitude.toRawBits())
            .putLong("lng", loc.longitude.toRawBits())
            .putLong("time_ms", timeMs)
            .apply()
    }

    var lastAccurateGnssFixTimeMs by remember { mutableLongStateOf(0L) }
    var candidateJumpLocation by remember { mutableStateOf<Location?>(null) }
    var candidateJumpCount by remember { mutableIntStateOf(0) }
    var sessionSavedCount by remember { mutableIntStateOf(0) }
    var gpsStatusText by remember { mutableStateOf("Locating GPS...") }
    var isGpsHealthy by remember { mutableStateOf(false) }

    fun refreshPermissionFlags() {
        hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        hasPhonePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionFlags()
        if (hasFineLocation || hasCoarseLocation) {
            latestSnapshot = readCurrentSignal(context)
        }
        if (!hasFineLocation && hasCoarseLocation) {
            gpsStatusText = "Approximate location only — enable Precise Location"
        } else if (!hasFineLocation && !hasCoarseLocation) {
            gpsStatusText = "Location permission denied"
        }
    }

    LaunchedEffect(Unit) {
        refreshPermissionFlags()
        if (hasFineLocation && hasPhonePermission) {
            latestSnapshot = readCurrentSignal(context)
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    LaunchedEffect(locateRequestCounter) {
        if (locateRequestCounter > 0 && hasAnyLocation) {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            try {
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null && loc.accuracy in 1f..MAX_DISPLAY_DOT_ACCURACY_METERS) {
                            currentLocation = loc
                            onLocationUpdated?.invoke(loc.latitude, loc.longitude, loc.accuracy)
                        }
                    }
            } catch (_: SecurityException) {
            }
        }
    }

    DisposableEffect(
        hasFineLocation,
        hasCoarseLocation,
        isAutoLogging,
        preferPureSatelliteGps,
        lockToBsuCampusArea,
        lifecycleOwner
    ) {
        if (!hasFineLocation && !hasCoarseLocation) {
            onDispose { }
        } else {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateIntervalMillis(1000L)
                .setMaxUpdateAgeMillis(10_000L)
                .build()

            fun isLocationStale(loc: Location): Boolean {
                val elapsedNanos = loc.elapsedRealtimeNanos
                if (elapsedNanos > 0L) {
                    val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedNanos) / 1_000_000L
                    return ageMs > MAX_LOCATION_AGE_MS
                }
                val wallAgeMs = System.currentTimeMillis() - loc.time
                return wallAgeMs > MAX_LOCATION_AGE_MS
            }

            fun handleNewLocation(loc: Location, isFromHardwareGps: Boolean) {
                val snapshot = readCurrentSignal(context)
                latestSnapshot = snapshot

                if (isLocationStale(loc)) return

                @Suppress("DEPRECATION")
                if (loc.isFromMockProvider) {
                    isGpsHealthy = false
                    gpsStatusText = "Rejected mock location fix"
                    return
                }

                val nowMs = System.currentTimeMillis()
                val isAccurateForSaving =
                    loc.accuracy > 0f && loc.accuracy <= MAX_ALLOWED_SAVE_ACCURACY_METERS

                if (isFromHardwareGps && isAccurateForSaving) {
                    lastAccurateGnssFixTimeMs = nowMs
                } else if (!isFromHardwareGps &&
                    preferPureSatelliteGps &&
                    (nowMs - lastAccurateGnssFixTimeMs) < 8_000L
                ) {
                    return
                }

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
                        isGpsHealthy = false
                        gpsStatusText = "Blocked teleport (${distFromBsu[0].toInt()}m from BSU)"
                        return
                    }
                }

                if (loc.accuracy <= 0f || loc.accuracy > MAX_DISPLAY_DOT_ACCURACY_METERS) {
                    isGpsHealthy = false
                    val holdTag = if (currentLocation != null) " · Holding last spot" else ""
                    gpsStatusText = "Standby: Coarse signal (±${loc.accuracy.toInt()}m)$holdTag"
                    return
                }

                val prevVerified = currentLocation
                if (prevVerified != null && prevVerified.accuracy <= MAX_ALLOWED_SAVE_ACCURACY_METERS) {
                    val jumpDist = prevVerified.distanceTo(loc)
                    val dtSec = if (loc.elapsedRealtimeNanos > 0L && prevVerified.elapsedRealtimeNanos > 0L) {
                        ((loc.elapsedRealtimeNanos - prevVerified.elapsedRealtimeNanos) / 1_000_000_000.0)
                            .coerceAtLeast(0.5)
                    } else {
                        ((loc.time - prevVerified.time) / 1000.0).coerceAtLeast(0.5)
                    }
                    val speedMps = jumpDist / dtSec

                    if (dtSec <= 15.0 &&
                        jumpDist > TELEPORT_DISTANCE_THRESHOLD_METERS &&
                        speedMps > MAX_PLAUSIBLE_SPEED_MPS
                    ) {
                        if (isAccurateForSaving) {
                            val candidate = candidateJumpLocation
                            if (candidate != null && candidate.distanceTo(loc) <= 50f) {
                                candidateJumpCount++
                            } else {
                                candidateJumpLocation = loc
                                candidateJumpCount = 1
                            }

                            if (candidateJumpCount < REQUIRED_CONSECUTIVE_FIXES_FOR_LARGE_JUMP) {
                                gpsStatusText = "Filtered GPS spike (${jumpDist.toInt()}m jump)"
                                return
                            }
                            candidateJumpLocation = null
                            candidateJumpCount = 0
                        } else {
                            return
                        }
                    } else {
                        candidateJumpLocation = null
                        candidateJumpCount = 0
                    }
                } else {
                    candidateJumpLocation = null
                    candidateJumpCount = 0
                }

                currentLocation = loc
                onLocationUpdated?.invoke(loc.latitude, loc.longitude, loc.accuracy)
                val sourceTag = if (isFromHardwareGps) "GNSS" else "GPS"

                if (!isAccurateForSaving) {
                    isGpsHealthy = false
                    gpsStatusText = if (!hasFineLocation) {
                        "Approximate only (±${loc.accuracy.toInt()}m) — Enable Precise GPS"
                    } else {
                        "Standby: Weak GPS (±${loc.accuracy.toInt()}m > 30m)"
                    }
                    return
                }

                isGpsHealthy = true

                if (!isAutoLogging) {
                    gpsStatusText = "Paused · $sourceTag ±${loc.accuracy.toInt()}m"
                    return
                }

                val prevSaved = lastVerifiedSaveLocation
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
                    persistLastSavedAnchor(loc, nowMs)
                    sessionSavedCount++
                    gpsStatusText =
                        "Auto-Recording · Saved #$sessionSavedCount · $sourceTag ±${loc.accuracy.toInt()}m"
                } else {
                    gpsStatusText =
                        "Auto-Recording · ${movedMeters.toInt()}m/20m · $sourceTag ±${loc.accuracy.toInt()}m"
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
                refreshPermissionFlags()
                try {
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { loc ->
                            if (loc != null) {
                                handleNewLocation(loc, isFromHardwareGps = false)
                            }
                        }

                    fusedClient.requestLocationUpdates(
                        locationRequest,
                        fusedCallback,
                        Looper.getMainLooper()
                    )

                    if (hasFineLocation &&
                        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ) {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            2000L,
                            0f,
                            hardwareGpsListener,
                            Looper.getMainLooper()
                        )
                    } else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        gpsStatusText = "GPS is OFF — turn on phone Location"
                    }
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

            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                startLocationUpdates()
            }

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                stopLocationUpdates()
            }
        }
    }

    val snap = latestSnapshot
    val signalAccentColor = when {
        snap == null -> MaterialTheme.colorScheme.primary
        snap.networkType == "2G" || snap.level < 2 -> SignalWeakRed
        snap.level >= 3 -> SignalStrongGreen
        else -> SignalFairAmber
    }

    // Keep GPS & signal engine alive even while the visual card smoothly hides for a square sheet!
    AnimatedVisibility(
        visible = isCardVisible,
        enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) +
            expandVertically(tween(220, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(180, easing = FastOutSlowInEasing)) +
            shrinkVertically(tween(180, easing = FastOutSlowInEasing))
    ) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Sleek mockup sheet handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (!hasFineLocation || !hasPhonePermission) {
                Text(
                    text = if (hasCoarseLocation && !hasFineLocation) {
                        "Precise GPS Required"
                    } else {
                        "Permissions Needed"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (hasCoarseLocation && !hasFineLocation) {
                        "Approximate location is selected. Allow \"Precise\" location to map exact 22m squares."
                    } else {
                        "Allow Precise Location and Phone access to measure live signal and map campus squares."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { permissionLauncher.launch(requiredPermissions) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(
                        if (hasCoarseLocation && !hasFineLocation) {
                            "Enable Precise Location"
                        } else {
                            "Grant Location & Phone Permissions"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                // Row 1: Signal Meter + Carrier/Badge/dBm + Wi-Fi Pill with custom icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SignalStrengthBars(
                            level = snap?.level ?: 0,
                            activeColor = signalAccentColor
                        )
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = snap?.carrier ?: "Reading...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = snap?.networkType ?: "--",
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            val dbmLabel = if (snap != null && snap.dbm < 0) "${snap.dbm} dBm" else "— dBm"
                            Text(
                                text = "$dbmLabel · ${snap?.level ?: 0}/4 bars",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Wi-Fi pill with ic_wifi_on / ic_wifi_off
                    val wifiDbm = snap?.wifiDbm
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (wifiDbm != null) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = if (wifiDbm != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                text = if (wifiDbm != null) "$wifiDbm dBm" else "Wi-Fi Off",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(9.dp))

                // Row 2: Live GPS / Auto-Record status indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    val dotColor = when {
                        !isAutoLogging -> SignalFairAmber
                        isGpsHealthy -> SignalStrongGreen
                        else -> SignalFairAmber
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    val queueSuffix =
                        if (pendingOfflineCount > 0) " · $pendingOfflineCount queued" else ""
                    Text(
                        text = "$gpsStatusText$queueSuffix",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(11.dp))

                // Row 3: Mockup-styled Rounded Pill Action Row (Record Spot | Pause | Options)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val loc = currentLocation
                            val snapshot = readCurrentSignal(context)
                            latestSnapshot = snapshot
                            if (loc != null && loc.accuracy <= MAX_ALLOWED_SAVE_ACCURACY_METERS) {
                                val reading = snapshot.toSignalReading(
                                    lat = loc.latitude,
                                    lng = loc.longitude,
                                    accuracy = loc.accuracy
                                )
                                onReadingCaptured?.invoke(reading)
                                persistLastSavedAnchor(loc, System.currentTimeMillis())
                                sessionSavedCount++
                                gpsStatusText =
                                    "Recorded Spot #$sessionSavedCount (±${loc.accuracy.toInt()}m)"
                            } else if (loc != null) {
                                gpsStatusText =
                                    "GPS accuracy is ±${loc.accuracy.toInt()}m (needs ≤30m to record)"
                            } else {
                                gpsStatusText = "Waiting for GPS lock..."
                            }
                        },
                        modifier = Modifier.weight(1.25f),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_record_circle),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Record Spot",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    Surface(
                        onClick = {
                            isAutoLogging = !isAutoLogging
                            gpsStatusText = if (isAutoLogging) {
                                "Auto-Recording resumed"
                            } else {
                                "Paused · Tap Resume to auto-log"
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isAutoLogging) R.drawable.ic_pause_bars else R.drawable.ic_play_arrow
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isAutoLogging) "Pause" else "Resume",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Surface(
                        onClick = { setShowOptions(!effectiveShowOptions) },
                        shape = RoundedCornerShape(24.dp),
                        color = if (effectiveShowOptions) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (effectiveShowOptions) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_dots),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (effectiveShowOptions) "Hide" else "Options",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Expandable Options Drawer (matches map panel enter/exit timing)
                AnimatedVisibility(
                    visible = effectiveShowOptions,
                    enter = fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                        expandVertically(tween(240, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(200, easing = FastOutSlowInEasing)) +
                        shrinkVertically(tween(200, easing = FastOutSlowInEasing))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "GPS & Field-Test Controls",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = preferPureSatelliteGps,
                                onClick = { onTogglePureGnss?.invoke(!preferPureSatelliteGps) },
                                label = {
                                    Text(
                                        text = "Pure GNSS",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                            FilterChip(
                                selected = lockToBsuCampusArea,
                                onClick = { onToggleBsuCampusLock?.invoke(!lockToBsuCampusArea) },
                                label = {
                                    Text(
                                        text = "BSU Campus Lock",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { latestSnapshot = readCurrentSignal(context) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("Refresh Signal", style = MaterialTheme.typography.labelMedium)
                            }
                            if (onSendTestReading != null) {
                                OutlinedButton(
                                    onClick = onSendTestReading,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text("Send Test Reading", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * Standalone wrapper preserved for Member 1 testing compatibility.
 */
@Composable
fun SignalCollectorScreen(
    modifier: Modifier = Modifier,
    pendingOfflineCount: Int = 0,
    onReadingCaptured: ((SignalReading) -> Unit)? = null,
    onLocationUpdated: ((lat: Double, lng: Double, accuracy: Float) -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            SignalCollectorBottomCard(
                pendingOfflineCount = pendingOfflineCount,
                onReadingCaptured = onReadingCaptured,
                onLocationUpdated = onLocationUpdated
            )
        }
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

    val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
    val telephonyManager = if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        baseTelephonyManager.createForSubscriptionId(dataSubId)
    } else {
        baseTelephonyManager
    }

    val rawCarrier = telephonyManager.networkOperatorName?.trim().orEmpty()
    val simCarrier = telephonyManager.simOperatorName?.trim().orEmpty()
    val chosenCarrier = when {
        rawCarrier.isNotEmpty() -> rawCarrier
        simCarrier.isNotEmpty() -> simCarrier
        else -> "Unknown"
    }
    val carrier = normalizeCarrierName(chosenCarrier)

    val signalStrength = telephonyManager.signalStrength
    val level = (signalStrength?.level ?: 0).coerceIn(0, 4)

    val cellSignalList: List<CellSignalStrength> =
        signalStrength?.cellSignalStrengths ?: emptyList()

    val dataGen = try {
        mapNetworkTypeToGeneration(telephonyManager.dataNetworkType)
    } catch (_: SecurityException) {
        "Unknown"
    }
    val networkType = if (dataGen != "Unknown") {
        dataGen
    } else {
        inferGenerationFromCellSignal(cellSignalList)
    }

    val rawDbm = cellSignalList.firstOrNull {
        it.dbm != CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN && it.dbm != Int.MAX_VALUE
    }?.dbm ?: Int.MAX_VALUE

    val isDbmValid = rawDbm != Int.MAX_VALUE && rawDbm < 0
    val dbmDisplay = if (isDbmValid) "$rawDbm dBm" else "— dBm"
    val cleanDbmValue = if (isDbmValid) rawDbm else 0

    val wifiDbm = readConnectedWifiDbm(appContext)
    val wifiDisplay = if (wifiDbm != null) "$wifiDbm dBm" else "Not connected"

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

private fun inferGenerationFromCellSignal(cellSignals: List<CellSignalStrength>): String {
    return when {
        cellSignals.any { it is CellSignalStrengthNr } -> "5G"
        cellSignals.any { it is CellSignalStrengthLte } -> "4G"
        cellSignals.any { it is CellSignalStrengthWcdma || it is CellSignalStrengthTdscdma } -> "3G"
        cellSignals.any { it is CellSignalStrengthGsm || it is CellSignalStrengthCdma } -> "2G"
        else -> "Unknown"
    }
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
