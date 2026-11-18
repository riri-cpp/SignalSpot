package com.example.signalspot.collector

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.CellSignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.signalspot.data.SignalReading
import com.example.signalspot.data.gridKeyFor
import java.util.UUID

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
 * Week 1 standalone test screen for Member 1 (SignalCollector.kt).
 */
@Composable
fun SignalCollectorScreen(modifier: Modifier = Modifier) {
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

    // Request permissions automatically when the app starts
    LaunchedEffect(Unit) {
        if (hasPermissions) {
            displayOutput = readCurrentSignal(context).formattedSummary
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SignalSpot — Signal Reader",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = displayOutput,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

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
        }
    }
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

    // 1. Carrier Name
    val rawCarrier = telephonyManager.networkOperatorName?.trim().orEmpty()
    val simCarrier = telephonyManager.simOperatorName?.trim().orEmpty()
    val carrier = when {
        rawCarrier.isNotEmpty() -> rawCarrier
        simCarrier.isNotEmpty() -> simCarrier
        else -> "Unknown Carrier"
    }

    // 2. Network Type (2G / 3G / 4G / 5G)
    val networkType = try {
        mapNetworkTypeToGeneration(telephonyManager.dataNetworkType)
    } catch (e: SecurityException) {
        "Unknown"
    }

    // 3. Signal Strength (0-4 bars and exact dBm)
    val signalStrength = telephonyManager.signalStrength
    val level = signalStrength?.level ?: 0

    val cellSignalList: List<CellSignalStrength> =
        signalStrength?.cellSignalStrengths ?: emptyList()
    val rawDbm = cellSignalList.firstOrNull {
        it.dbm != CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN && it.dbm != Int.MAX_VALUE
    }?.dbm ?: Int.MAX_VALUE

    // INVALID DBM FALLBACK:
    // If exact dBm returns 2147483647 (Int.MAX_VALUE) or is invalid, fall back to "-" for dBm
    val isDbmValid = rawDbm != Int.MAX_VALUE && rawDbm < 0
    val dbmDisplay = if (isDbmValid) "$rawDbm dBm" else "- dBm"
    val cleanDbmValue = if (isDbmValid) rawDbm else 0

    // 4. Wi-Fi Signal Reading
    val wifiDbm = readConnectedWifiDbm(appContext)
    val wifiDisplay = if (wifiDbm != null) "$wifiDbm dBm" else "Not connected"

    // Target output format: Smart 4G -92 dBm | 3 bars | Wi-Fi: -61 dBm
    val formattedSummary = "$carrier $networkType $dbmDisplay | $level bars | Wi-Fi: $wifiDisplay"

    return RawSignalSnapshot(
        carrier = carrier,
        networkType = networkType,
        dbm = cleanDbmValue,
        level = level,
        wifiDbm = wifiDbm,
        formattedSummary = formattedSummary
    )
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