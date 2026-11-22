package com.example.signalspot.map

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.location.Location
import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.signalspot.data.GRID_SIZE
import com.example.signalspot.data.SignalReading
import com.example.signalspot.data.gridKeyFor
import java.util.Locale
import kotlin.math.floor
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

// Approximate center of BatStateU Alangilan.
private const val CAMPUS_LAT = 13.7847
private const val CAMPUS_LON = 121.0735

private enum class SignalLevel(val label: String, val fillColor: Int) {
    STRONG("Strong", Color.argb(120, 0, 200, 90)),     // green
    FAIR("Fair", Color.argb(120, 255, 170, 0)),        // yellow/orange
    WEAK("Weak / 2G", Color.argb(120, 230, 30, 30))    // red
}

private data class GridSquareSummary(
    val gridKey: String,
    val row: Long,
    val col: Long,
    val level: SignalLevel,
    val avgBars: Double,
    val allReadingsInSquare: List<SignalReading>,
    val filteredReadings: List<SignalReading>
) {
    val centerLat: Double get() = (row + 0.5) * GRID_SIZE
    val centerLng: Double get() = (col + 0.5) * GRID_SIZE
}

/**
 * Groups real Firestore [SignalReading] records into ~22 m x 22 m squares (GRID_SIZE = 0.0002)
 * and colors each square per Proposal Part 5A (Strong >= 3.0 bars, Fair 1.5..2.9 bars, Weak < 1.5 or 2G).
 */
private fun buildGridSquares(
    allReadings: List<SignalReading>,
    carrierFilter: String
): List<GridSquareSummary> {
    val validReadings = allReadings.filter { it.lat != 0.0 || it.lng != 0.0 }
    val groupedAll = validReadings.groupBy { reading ->
        reading.gridKey.ifBlank { gridKeyFor(reading.lat, reading.lng) }
    }

    val summaries = mutableListOf<GridSquareSummary>()

    for ((key, squareReadings) in groupedAll) {
        val filtered = if (carrierFilter == "All") {
            squareReadings
        } else {
            squareReadings.filter { it.carrier.equals(carrierFilter, ignoreCase = true) }
        }
        if (filtered.isEmpty()) continue

        val sample = filtered.first()
        val row = floor(sample.lat / GRID_SIZE).toLong()
        val col = floor(sample.lng / GRID_SIZE).toLong()

        val avgBars = filtered.map { it.level }.average()
        val latestIs2G = filtered.maxByOrNull { it.timestamp }?.networkType == "2G"

        val signalLevel = when {
            latestIs2G || avgBars < 1.5 -> SignalLevel.WEAK
            avgBars >= 3.0 -> SignalLevel.STRONG
            else -> SignalLevel.FAIR
        }

        summaries.add(
            GridSquareSummary(
                gridKey = key,
                row = row,
                col = col,
                level = signalLevel,
                avgBars = avgBars,
                allReadingsInSquare = squareReadings,
                filteredReadings = filtered
            )
        )
    }

    return summaries
}

private fun buildRealSquarePolygon(
    summary: GridSquareSummary,
    onSquareTapped: (GridSquareSummary) -> Unit
): Polygon {
    val south = summary.row * GRID_SIZE
    val west = summary.col * GRID_SIZE
    val north = south + GRID_SIZE
    val east = west + GRID_SIZE

    return Polygon().apply {
        points = listOf(
            GeoPoint(south, west),
            GeoPoint(south, east),
            GeoPoint(north, east),
            GeoPoint(north, west)
        )
        fillPaint.color = summary.level.fillColor
        outlinePaint.color = Color.argb(180, 0, 0, 0)
        outlinePaint.strokeWidth = 2f
        setOnClickListener { _, _, _ ->
            onSquareTapped(summary)
            true
        }
    }
}

private fun buildUserLocationDot(lat: Double, lng: Double): Polygon {
    val delta = 0.00004
    return Polygon().apply {
        points = listOf(
            GeoPoint(lat - delta, lng - delta),
            GeoPoint(lat - delta, lng + delta),
            GeoPoint(lat + delta, lng + delta),
            GeoPoint(lat + delta, lng - delta)
        )
        fillPaint.color = Color.argb(230, 30, 120, 255)
        outlinePaint.color = Color.WHITE
        outlinePaint.strokeWidth = 4f
    }
}

// Finds the Activity (which is a LifecycleOwner) behind a Context.
private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is LifecycleOwner) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun SignalHeatmapView(
    modifier: Modifier = Modifier,
    readings: List<SignalReading> = emptyList(),
    userLat: Double? = null,
    userLng: Double? = null
) {
    val context = LocalContext.current
    val carriers = listOf("All", "Smart", "Globe", "DITO")
    var selectedCarrier by remember { mutableStateOf("All") }
    var selectedSquare by remember { mutableStateOf<GridSquareSummary?>(null) }
    var hasAutoCenteredOnUser by remember { mutableStateOf(false) }

    // Required, or map tiles won't load. remember{} makes it run once.
    remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", 0))
            userAgentValue = context.packageName
        }
    }

    val mapView = remember {
        MapView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(19.0)
            controller.setCenter(GeoPoint(CAMPUS_LAT, CAMPUS_LON))
        }
    }

    val gridSquares = remember(readings, selectedCarrier) {
        buildGridSquares(readings, selectedCarrier)
    }

    // Center on user's real GPS position once when first acquired
    LaunchedEffect(userLat, userLng) {
        if (!hasAutoCenteredOnUser && userLat != null && userLng != null &&
            (userLat != 0.0 || userLng != 0.0)
        ) {
            mapView.controller.animateTo(GeoPoint(userLat, userLng))
            hasAutoCenteredOnUser = true
        }
    }

    // Redraw map squares whenever Firestore readings, filter, or user location changes
    LaunchedEffect(gridSquares, userLat, userLng) {
        mapView.overlays.clear()
        gridSquares.forEach { summary ->
            mapView.overlays.add(
                buildRealSquarePolygon(summary) { tapped ->
                    selectedSquare = tapped
                }
            )
        }
        if (userLat != null && userLng != null && (userLat != 0.0 || userLng != 0.0)) {
            mapView.overlays.add(buildUserLocationDot(userLat, userLng))
        }
        mapView.invalidate()
    }

    // Tell the map when the app pauses/resumes, and clean up when done.
    val lifecycleOwner = context.findLifecycleOwner()
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            mapView.onDetach()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Top Filter Bar (Carrier filter chips + Square count)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            carriers.forEach { carrier ->
                FilterChip(
                    selected = selectedCarrier == carrier,
                    onClick = {
                        selectedCarrier = carrier
                        selectedSquare = null
                    },
                    label = { Text(carrier) }
                )
            }
        }

        // Floating Action Buttons: "My GPS" and "Nearest Strong Spot" (Proposal Objective #3)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            SmallFloatingActionButton(
                onClick = {
                    val refLat = userLat ?: mapView.mapCenter.latitude
                    val refLng = userLng ?: mapView.mapCenter.longitude
                    val strongSquares = gridSquares.filter { it.level == SignalLevel.STRONG }
                    val nearest = strongSquares.minByOrNull { sq ->
                        val results = FloatArray(1)
                        Location.distanceBetween(refLat, refLng, sq.centerLat, sq.centerLng, results)
                        results[0]
                    }
                    if (nearest != null) {
                        mapView.controller.animateTo(GeoPoint(nearest.centerLat, nearest.centerLng))
                        selectedSquare = nearest
                    }
                }
            ) {
                Text(
                    text = "Nearest Strong",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            SmallFloatingActionButton(
                onClick = {
                    mapView.controller.animateTo(GeoPoint(CAMPUS_LAT, CAMPUS_LON))
                }
            ) {
                Text(
                    text = "BSU Campus",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            if (userLat != null && userLng != null && (userLat != 0.0 || userLng != 0.0)) {
                SmallFloatingActionButton(
                    onClick = {
                        mapView.controller.animateTo(GeoPoint(userLat, userLng))
                    }
                ) {
                    Text(
                        text = "My GPS",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Square Tap Popup: Side-by-side Carrier Comparison + Last Updated (Proposal Objective #3 & Risk #6)
        val tapped = selectedSquare
        if (tapped != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(10.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val latestTime = tapped.allReadingsInSquare.maxOfOrNull { it.timestamp } ?: 0L
                    val relativeTime = if (latestTime > 0L) {
                        DateUtils.getRelativeTimeSpanString(
                            latestTime,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString()
                    } else {
                        "Just now"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${tapped.level.label} Square · ${
                                String.format(Locale.US, "%.1f", tapped.avgBars)
                            } avg bars (${tapped.filteredReadings.size} readings)",
                            style = MaterialTheme.typography.titleSmall
                        )
                        TextButton(onClick = { selectedSquare = null }) {
                            Text("Close")
                        }
                    }

                    Text(
                        text = "Updated $relativeTime · Carrier Comparison:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val byCarrier = listOf("Smart", "Globe", "DITO").map { cName ->
                        val cList = tapped.allReadingsInSquare.filter {
                            it.carrier.equals(cName, ignoreCase = true)
                        }
                        if (cList.isEmpty()) {
                            "$cName: No data"
                        } else {
                            val avgB = String.format(Locale.US, "%.1f", cList.map { it.level }.average())
                            val validDbms = cList.map { it.dbm }.filter { it < 0 }
                            val avgD = if (validDbms.isNotEmpty()) "${validDbms.average().toInt()} dBm" else "— dBm"
                            val net = cList.maxByOrNull { it.timestamp }?.networkType ?: ""
                            "$cName ($net): $avgB bars ($avgD)"
                        }
                    }

                    val wifiReadings = tapped.allReadingsInSquare.mapNotNull { it.wifiDbm }
                    val wifiSummary = if (wifiReadings.isNotEmpty()) {
                        "Wi-Fi: ${wifiReadings.average().toInt()} dBm"
                    } else {
                        "Wi-Fi: None"
                    }

                    Text(
                        text = (byCarrier + wifiSummary).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
