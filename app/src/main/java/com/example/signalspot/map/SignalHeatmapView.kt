package com.example.signalspot.map

import android.graphics.Color as AndroidColor
import android.location.Location
import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.signalspot.collector.SignalStrengthBars
import com.example.signalspot.data.GRID_SIZE
import com.example.signalspot.data.SignalReading
import com.example.signalspot.data.gridKeyFor
import com.example.signalspot.ui.theme.SignalFairAmber
import com.example.signalspot.ui.theme.SignalStrongGreen
import com.example.signalspot.ui.theme.SignalWeakRed
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

// Approximate center of BatStateU Alangilan.
private const val CAMPUS_LAT = 13.7847
private const val CAMPUS_LON = 121.0735

private enum class SignalLevel(
    val label: String,
    val fillColor: Int,
    val composeColor: Color
) {
    STRONG("Strong", AndroidColor.argb(125, 22, 163, 74), SignalStrongGreen),
    FAIR("Fair", AndroidColor.argb(125, 217, 119, 6), SignalFairAmber),
    WEAK("Weak / 2G", AndroidColor.argb(125, 220, 38, 38), SignalWeakRed)
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
 * filtered by both Carrier and Network Type (Proposal Week 2 & Part 5A).
 */
private fun buildGridSquares(
    allReadings: List<SignalReading>,
    carrierFilter: String,
    networkFilter: String
): List<GridSquareSummary> {
    val validReadings = allReadings.filter { it.lat != 0.0 || it.lng != 0.0 }
    val groupedAll = validReadings.groupBy { reading ->
        reading.gridKey.ifBlank { gridKeyFor(reading.lat, reading.lng) }
    }

    val summaries = mutableListOf<GridSquareSummary>()

    for ((key, squareReadings) in groupedAll) {
        val filtered = squareReadings.filter { r ->
            val carrierMatches =
                carrierFilter == "All" || r.carrier.equals(carrierFilter, ignoreCase = true)
            val networkMatches =
                networkFilter == "All" || r.networkType.equals(networkFilter, ignoreCase = true)
            carrierMatches && networkMatches
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
    isSelected: Boolean,
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
        outlinePaint.color = if (isSelected) {
            AndroidColor.rgb(15, 23, 42)
        } else {
            AndroidColor.argb(140, 15, 23, 42)
        }
        outlinePaint.strokeWidth = if (isSelected) 5f else 2f
        setOnClickListener { _, _, _ ->
            onSquareTapped(summary)
            true
        }
    }
}

private fun buildUserAccuracyHalo(lat: Double, lng: Double, accuracyMeters: Float): Polygon {
    val radiusMeters = accuracyMeters.toDouble().coerceIn(8.0, 150.0)
    return Polygon().apply {
        points = Polygon.pointsAsCircle(GeoPoint(lat, lng), radiusMeters)
        fillPaint.color = AndroidColor.argb(45, 21, 94, 239)
        outlinePaint.color = AndroidColor.argb(110, 21, 94, 239)
        outlinePaint.strokeWidth = 2f
    }
}

private fun buildUserLocationDot(lat: Double, lng: Double): Polygon {
    return Polygon().apply {
        points = Polygon.pointsAsCircle(GeoPoint(lat, lng), 4.5)
        fillPaint.color = AndroidColor.argb(245, 21, 94, 239)
        outlinePaint.color = AndroidColor.WHITE
        outlinePaint.strokeWidth = 5f
    }
}

private fun moveCameraSmoothly(mapView: MapView, target: GeoPoint, targetZoom: Double = 18.8) {
    mapView.controller.stopAnimation(false)
    val currentCenter = mapView.mapCenter
    val dist = FloatArray(1)
    Location.distanceBetween(
        currentCenter.latitude,
        currentCenter.longitude,
        target.latitude,
        target.longitude,
        dist
    )
    // If jumping far away (e.g., > 2 km from home to BSU Campus), snap directly to avoid stalls
    if (dist[0] > 2000f) {
        mapView.controller.setZoom(targetZoom)
        mapView.controller.setCenter(target)
    } else {
        mapView.controller.animateTo(target, targetZoom, 450L)
    }
    mapView.invalidate()
}

@Composable
fun SignalHeatmapView(
    modifier: Modifier = Modifier,
    readings: List<SignalReading> = emptyList(),
    pendingOfflineCount: Int = 0,
    syncErrorMessage: String? = null,
    userLat: Double? = null,
    userLng: Double? = null,
    userAccuracyMeters: Float? = null,
    bottomControlsPadding: Dp = 12.dp,
    onRequestMyLocation: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val carriers = listOf("All", "Smart", "Globe", "DITO")
    val networks = listOf("All", "5G", "4G", "3G", "2G")

    var selectedCarrier by remember { mutableStateOf("All") }
    var selectedNetwork by remember { mutableStateOf("All") }
    var selectedSquareKey by remember { mutableStateOf<String?>(null) }
    var hasAutoCenteredOnUser by remember { mutableStateOf(false) }
    var mapNoticeMessage by remember { mutableStateOf<String?>(null) }
    var popupCardHeightPx by remember { mutableIntStateOf(0) }
    val popupCardHeightDp = with(LocalDensity.current) { popupCardHeightPx.toDp() }

    // Auto-dismiss transient map notices after 3 seconds
    LaunchedEffect(mapNoticeMessage) {
        if (mapNoticeMessage != null) {
            delay(3000L)
            mapNoticeMessage = null
        }
    }

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
            controller.setZoom(18.5)
            controller.setCenter(GeoPoint(CAMPUS_LAT, CAMPUS_LON))
        }
    }

    val gridSquares = remember(readings, selectedCarrier, selectedNetwork) {
        buildGridSquares(readings, selectedCarrier, selectedNetwork)
    }

    val selectedSquare = remember(gridSquares, selectedSquareKey) {
        gridSquares.firstOrNull { it.gridKey == selectedSquareKey }
    }

    // Center on user's real GPS position once when first acquired
    LaunchedEffect(userLat, userLng) {
        if (!hasAutoCenteredOnUser && userLat != null && userLng != null &&
            (userLat != 0.0 || userLng != 0.0)
        ) {
            moveCameraSmoothly(mapView, GeoPoint(userLat, userLng), 18.8)
            hasAutoCenteredOnUser = true
        }
    }

    // Redraw map squares + user GPS halo & dot whenever readings, filters, selection, or GPS changes
    LaunchedEffect(gridSquares, selectedSquareKey, userLat, userLng, userAccuracyMeters) {
        mapView.overlays.clear()
        gridSquares.forEach { summary ->
            mapView.overlays.add(
                buildRealSquarePolygon(
                    summary = summary,
                    isSelected = summary.gridKey == selectedSquareKey
                ) { tapped ->
                    selectedSquareKey = tapped.gridKey
                }
            )
        }
        if (userLat != null && userLng != null && (userLat != 0.0 || userLng != 0.0)) {
            if (userAccuracyMeters != null && userAccuracyMeters > 0f) {
                mapView.overlays.add(buildUserAccuracyHalo(userLat, userLng, userAccuracyMeters))
            }
            mapView.overlays.add(buildUserLocationDot(userLat, userLng))
        }
        mapView.invalidate()
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    val hasLiveGps = userLat != null && userLng != null && (userLat != 0.0 || userLng != 0.0)
    // Shift floating action buttons cleanly above the comparison card whenever a square is selected
    val buttonsBottomPadding = if (selectedSquare != null) {
        bottomControlsPadding + popupCardHeightDp + 8.dp
    } else {
        bottomControlsPadding
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Full-screen OpenStreetMap
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Floating Header + Carrier & Network Filter Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = "SignalSpot",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "· ${gridSquares.size} spots",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val (pillText, pillColor) = when {
                        syncErrorMessage != null -> "Check Rules" to SignalWeakRed
                        pendingOfflineCount > 0 -> "$pendingOfflineCount Queued" to SignalFairAmber
                        else -> "Synced (${readings.size})" to SignalStrongGreen
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = pillColor.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(pillColor)
                            )
                            Text(
                                text = pillText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = pillColor
                            )
                        }
                    }
                }
            }

            // Carrier Filter Row (All | Smart | Globe | DITO)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                carriers.forEach { carrier ->
                    ElevatedFilterChip(
                        selected = selectedCarrier == carrier,
                        onClick = {
                            selectedCarrier = carrier
                            selectedSquareKey = null
                        },
                        label = {
                            Text(
                                text = carrier,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedCarrier == carrier) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.elevatedFilterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        elevation = FilterChipDefaults.elevatedFilterChipElevation(elevation = 3.dp)
                    )
                }
            }

            // Network Type Filter Row (All | 5G | 4G | 3G | 2G)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                networks.forEach { net ->
                    ElevatedFilterChip(
                        selected = selectedNetwork == net,
                        onClick = {
                            selectedNetwork = net
                            selectedSquareKey = null
                        },
                        label = {
                            Text(
                                text = if (net == "All") "All Nets" else net,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selectedNetwork == net) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.elevatedFilterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        elevation = FilterChipDefaults.elevatedFilterChipElevation(elevation = 2.dp)
                    )
                }
            }

            // Transient Map Feedback Banner (e.g. "No Strong spots found" or "Locating GPS...")
            AnimatedVisibility(visible = mapNoticeMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = mapNoticeMessage.orEmpty(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // 3. Bottom-Left Compact Legend Pill (hidden while square popup covers bottom area)
        if (selectedSquare == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = bottomControlsPadding),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendDot("Strong", SignalStrongGreen)
                    LegendDot("Fair", SignalFairAmber)
                    LegendDot("Weak", SignalWeakRed)
                }
            }
        }

        // 4. Bottom-Right Floating Map Action Pills — ALWAYS visible so user can freely toggle!
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = buttonsBottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            MapPillButton(
                text = "Nearest Strong",
                containerColor = SignalStrongGreen,
                contentColor = Color.White,
                onClick = {
                    val refLat = if (hasLiveGps) userLat else mapView.mapCenter.latitude
                    val refLng = if (hasLiveGps) userLng else mapView.mapCenter.longitude
                    val strongSquares = gridSquares.filter { it.level == SignalLevel.STRONG }
                    val nearest = strongSquares.minByOrNull { sq ->
                        val results = FloatArray(1)
                        Location.distanceBetween(
                            refLat,
                            refLng,
                            sq.centerLat,
                            sq.centerLng,
                            results
                        )
                        results[0]
                    }
                    if (nearest != null) {
                        moveCameraSmoothly(
                            mapView,
                            GeoPoint(nearest.centerLat, nearest.centerLng),
                            19.0
                        )
                        selectedSquareKey = nearest.gridKey
                    } else {
                        mapNoticeMessage = "No Strong (green) squares found for this filter"
                    }
                }
            )

            MapPillButton(
                text = "BSU Campus",
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    selectedSquareKey = null
                    moveCameraSmoothly(mapView, GeoPoint(CAMPUS_LAT, CAMPUS_LON), 18.5)
                }
            )

            MapPillButton(
                text = if (hasLiveGps) "My GPS" else "Find GPS",
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = {
                    selectedSquareKey = null
                    onRequestMyLocation?.invoke()
                    if (hasLiveGps) {
                        moveCameraSmoothly(mapView, GeoPoint(userLat, userLng), 19.0)
                    } else {
                        mapNoticeMessage = "Locating your GPS position..."
                    }
                }
            )
        }

        // 5. Upgraded Square-Tap Carrier Comparison Sheet/Card
        AnimatedVisibility(
            visible = selectedSquare != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = bottomControlsPadding)
                .onGloballyPositioned { coords ->
                    popupCardHeightPx = coords.size.height
                }
        ) {
            val tapped = selectedSquare
            if (tapped != null) {
                SquareComparisonCard(
                    summary = tapped,
                    onClose = { selectedSquareKey = null }
                )
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MapPillButton(
    text: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 5.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SquareComparisonCard(
    summary: GridSquareSummary,
    onClose: () -> Unit
) {
    val latestTime = summary.allReadingsInSquare.maxOfOrNull { it.timestamp } ?: 0L
    val relativeTime = if (latestTime > 0L) {
        DateUtils.getRelativeTimeSpanString(
            latestTime,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    } else {
        "Just now"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = summary.level.composeColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = summary.level.label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = summary.level.composeColor
                        )
                    }
                    Column {
                        Text(
                            text = "${
                                String.format(Locale.US, "%.1f", summary.avgBars)
                            } avg bars · ${summary.filteredReadings.size} readings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Updated $relativeTime",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(
                    onClick = onClose,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            listOf("Smart", "Globe", "DITO").forEach { cName ->
                val cList = summary.allReadingsInSquare.filter {
                    it.carrier.equals(cName, ignoreCase = true)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = cName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (cList.isNotEmpty()) {
                            val net = cList.maxByOrNull { it.timestamp }?.networkType ?: ""
                            if (net.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = net,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (cList.isEmpty()) {
                        Text(
                            text = "No data yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val avgB = cList.map { it.level }.average()
                        val validDbms = cList.map { it.dbm }.filter { it < 0 }
                        val avgD = if (validDbms.isNotEmpty()) {
                            "${validDbms.average().toInt()} dBm"
                        } else {
                            "— dBm"
                        }
                        val barColor = when {
                            avgB >= 3.0 -> SignalStrongGreen
                            avgB >= 1.5 -> SignalFairAmber
                            else -> SignalWeakRed
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${String.format(Locale.US, "%.1f", avgB)} bars ($avgD)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            SignalStrengthBars(
                                level = avgB.roundToInt(),
                                activeColor = barColor
                            )
                        }
                    }
                }
            }

            val wifiReadings = summary.allReadingsInSquare.mapNotNull { it.wifiDbm }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Wi-Fi",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (wifiReadings.isNotEmpty()) {
                        "Avg ${wifiReadings.average().toInt()} dBm (${wifiReadings.size} samples)"
                    } else {
                        "No Wi-Fi readings"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
