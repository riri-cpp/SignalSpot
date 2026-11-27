package com.example.signalspot

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.signalspot.collector.SignalCollectorBottomCard
import com.example.signalspot.collector.SignalStrengthBars
import com.example.signalspot.data.ReadingRepository
import com.example.signalspot.data.SignalReading
import com.example.signalspot.data.gridKeyFor
import com.example.signalspot.map.SignalHeatmapView
import com.example.signalspot.ui.theme.BrandCyanGlow
import com.example.signalspot.ui.theme.BrandNavBarNavy
import com.example.signalspot.ui.theme.BrandNavy
import com.example.signalspot.ui.theme.BrandTealBright
import com.example.signalspot.ui.theme.SignalFairAmber
import com.example.signalspot.ui.theme.SignalSpotTheme
import com.example.signalspot.ui.theme.SignalStrongGreen
import com.example.signalspot.ui.theme.SignalWeakRed
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay

private enum class AppNavTab(val label: String, @DrawableRes val iconRes: Int) {
    MAP("Map", R.drawable.ic_pin_gps),
    STATS("Stats", R.drawable.ic_nearest_strong),
    MORE("More", R.drawable.ic_more_dots)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SignalSpotTheme {
                val repository = remember { ReadingRepository() }
                var readings by remember { mutableStateOf<List<SignalReading>>(emptyList()) }
                var pendingOfflineCount by remember { mutableIntStateOf(0) }
                var syncErrorMessage by remember { mutableStateOf<String?>(null) }
                var userLat by remember { mutableStateOf<Double?>(null) }
                var userLng by remember { mutableStateOf<Double?>(null) }
                var userAccuracy by remember { mutableStateOf<Float?>(null) }
                var locateRequestCounter by remember { mutableIntStateOf(0) }
                var testCounter by remember { mutableIntStateOf(0) }

                // Shared app preferences controlled from both Map Options & More Settings
                var preferPureSatelliteGps by remember { mutableStateOf(true) }
                var lockToBsuCampusArea by remember { mutableStateOf(false) }
                var keepScreenAwake by remember { mutableStateOf(true) }

                var activeTab by remember { mutableStateOf(AppNavTab.MAP) }
                var showSplash by remember { mutableStateOf(true) }
                var isSquareSheetOpen by remember { mutableStateOf(false) }
                var isBottomOptionsOpen by remember { mutableStateOf(false) }

                var bottomOverlayHeightPx by remember { mutableIntStateOf(0) }
                val bottomOverlayHeightDp = with(LocalDensity.current) { bottomOverlayHeightPx.toDp() }
                var navBarOnlyHeightPx by remember { mutableIntStateOf(0) }
                val navBarOnlyHeightDp = with(LocalDensity.current) { navBarOnlyHeightPx.toDp() }
                val systemNavBarBottomDp =
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                LaunchedEffect(keepScreenAwake) {
                    if (keepScreenAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                LaunchedEffect(Unit) {
                    delay(1400L)
                    showSplash = false
                }

                DisposableEffect(repository) {
                    val registration =
                        repository.listenToReadingsWithStatus { list, pending, error ->
                            readings = list
                            pendingOfflineCount = pending
                            syncErrorMessage = error
                        }
                    onDispose {
                        registration.remove()
                    }
                }

                val sendFakeTestReading: () -> Unit = {
                    val carriers = listOf("Smart", "Globe", "DITO")
                    val carrier = carriers[testCounter % carriers.size]
                    val level = Random.nextInt(1, 5)
                    val dbm = -110 + (level * 12)
                    val lat = 13.7847 + Random.nextDouble(-0.0004, 0.0004)
                    val lng = 121.0735 + Random.nextDouble(-0.0004, 0.0004)

                    repository.saveReading(
                        SignalReading(
                            id = UUID.randomUUID().toString(),
                            lat = lat,
                            lng = lng,
                            gridKey = gridKeyFor(lat, lng),
                            accuracy = 8.0f,
                            carrier = carrier,
                            networkType = "4G",
                            dbm = dbm,
                            level = level,
                            wifiDbm = -62,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    testCounter++
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (activeTab) {
                            AppNavTab.MAP -> {
                                SignalHeatmapView(
                                    modifier = Modifier.fillMaxSize(),
                                    readings = readings,
                                    pendingOfflineCount = pendingOfflineCount,
                                    syncErrorMessage = syncErrorMessage,
                                    userLat = userLat,
                                    userLng = userLng,
                                    userAccuracyMeters = userAccuracy,
                                    bottomControlsPadding = bottomOverlayHeightDp + 10.dp,
                                    navBarOnlyPadding = navBarOnlyHeightDp + systemNavBarBottomDp + 10.dp,
                                    isBottomOptionsOpen = isBottomOptionsOpen,
                                    onSquareSelectionChanged = { open ->
                                        isSquareSheetOpen = open
                                        if (open) isBottomOptionsOpen = false
                                    },
                                    onRequestMyLocation = { locateRequestCounter++ },
                                    onOpenSettings = { activeTab = AppNavTab.MORE }
                                )
                            }

                            AppNavTab.STATS -> {
                                SignalStatsScreen(
                                    readings = readings,
                                    bottomPaddingDp = bottomOverlayHeightDp + 14.dp
                                )
                            }

                            AppNavTab.MORE -> {
                                SignalMoreScreen(
                                    totalReadings = readings.size,
                                    pendingOfflineCount = pendingOfflineCount,
                                    syncErrorMessage = syncErrorMessage,
                                    preferPureGnss = preferPureSatelliteGps,
                                    lockToBsuCampus = lockToBsuCampusArea,
                                    keepScreenAwake = keepScreenAwake,
                                    onTogglePureGnss = { preferPureSatelliteGps = it },
                                    onToggleBsuCampusLock = { lockToBsuCampusArea = it },
                                    onToggleKeepScreenAwake = { keepScreenAwake = it },
                                    bottomPaddingDp = bottomOverlayHeightDp + 14.dp,
                                    onSendTestReading = sendFakeTestReading
                                )
                            }
                        }

                        // Measure full bottom overlay BEFORE navigationBarsPadding()
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    bottomOverlayHeightPx = coords.size.height
                                }
                                .navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (activeTab == AppNavTab.MAP) {
                                SignalCollectorBottomCard(
                                    isCardVisible = !isSquareSheetOpen,
                                    showOptions = isBottomOptionsOpen,
                                    onToggleShowOptions = { isBottomOptionsOpen = it },
                                    pendingOfflineCount = pendingOfflineCount,
                                    locateRequestCounter = locateRequestCounter,
                                    preferPureSatelliteGps = preferPureSatelliteGps,
                                    lockToBsuCampusArea = lockToBsuCampusArea,
                                    onTogglePureGnss = { preferPureSatelliteGps = it },
                                    onToggleBsuCampusLock = { lockToBsuCampusArea = it },
                                    onReadingCaptured = { reading ->
                                        repository.saveReading(reading)
                                    },
                                    onLocationUpdated = { lat, lng, accuracy ->
                                        userLat = lat
                                        userLng = lng
                                        userAccuracy = accuracy
                                    },
                                    onSendTestReading = sendFakeTestReading
                                )
                            }

                            // Measure nav bar + system bar height so SquareComparisonCard sits cleanly right above it
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coords ->
                                        // Add system nav bar inset automatically via bottomOverlayHeightPx when card hidden,
                                        // or capture nav bar height directly:
                                        navBarOnlyHeightPx = coords.size.height
                                    }
                            ) {
                                SignalSpotBottomNavBar(
                                    activeTab = activeTab,
                                    onSelectTab = { activeTab = it }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = showSplash,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(400, easing = FastOutSlowInEasing))
                        ) {
                            SignalSpotSplashOverlay()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalSpotSplashOverlay() {
    var logoAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { logoAppeared = true }
    val logoScale by animateFloatAsState(
        targetValue = if (logoAppeared) 1f else 0.88f,
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "splashLogoScale"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_splash_signalspot),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.24f))

            Image(
                painter = painterResource(R.drawable.ic_signalspot_logo),
                contentDescription = "SignalSpot Emblem",
                modifier = Modifier
                    .size(104.dp)
                    .scale(logoScale)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = BrandNavy, fontWeight = FontWeight.ExtraBold)) {
                        append("Signal")
                    }
                    withStyle(SpanStyle(color = BrandTealBright, fontWeight = FontWeight.ExtraBold)) {
                        append("Spot")
                    }
                },
                fontSize = 36.sp,
                letterSpacing = (-0.5).sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Find. Record. Compare.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF475569)
            )

            Spacer(modifier = Modifier.weight(0.46f))

            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = BrandTealBright,
                trackColor = BrandTealBright.copy(alpha = 0.2f),
                strokeWidth = 3.dp
            )

            Spacer(modifier = Modifier.weight(0.14f))
        }
    }
}

@Composable
private fun SignalSpotBottomNavBar(
    activeTab: AppNavTab,
    onSelectTab: (AppNavTab) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        shape = RoundedCornerShape(28.dp),
        color = BrandNavBarNavy,
        contentColor = Color.White,
        shadowElevation = 12.dp,
        border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppNavTab.entries.forEach { tab ->
                val isSelected = activeTab == tab
                val tint = if (isSelected) BrandCyanGlow else Color(0xFF9AB0C6)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = tab.label,
                        modifier = Modifier.size(21.dp),
                        tint = tint
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = tint
                    )
                    Box(
                        modifier = Modifier
                            .width(26.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (isSelected) BrandCyanGlow else Color.Transparent)
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalStatsScreen(
    readings: List<SignalReading>,
    bottomPaddingDp: Dp
) {
    val validReadings = remember(readings) { readings.filter { it.lat != 0.0 || it.lng != 0.0 } }
    val uniqueSquares = remember(validReadings) {
        validReadings.map { it.gridKey.ifBlank { gridKeyFor(it.lat, it.lng) } }.toSet().size
    }
    val strongCount = remember(validReadings) {
        validReadings.count { it.networkType != "2G" && it.level >= 3 }
    }
    val fairCount = remember(validReadings) {
        validReadings.count { it.networkType != "2G" && it.level == 2 }
    }
    val weakCount = remember(validReadings) {
        validReadings.count { it.networkType == "2G" || it.level <= 1 }
    }
    val wifiReadings = remember(readings) { readings.mapNotNull { it.wifiDbm } }
    val cardBorder = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = bottomPaddingDp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_signalspot_logo),
                contentDescription = null,
                modifier = Modifier.size(34.dp)
            )
            Column {
                Text(
                    text = "Signal Analytics",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Live summary across all synced BatStateU spots",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Overview KPI Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatKpiCard(
                title = "Total Readings",
                value = readings.size.toString(),
                accent = MaterialTheme.colorScheme.primary,
                border = cardBorder,
                modifier = Modifier.weight(1f)
            )
            StatKpiCard(
                title = "Mapped Squares",
                value = uniqueSquares.toString(),
                accent = SignalStrongGreen,
                border = cardBorder,
                modifier = Modifier.weight(1f)
            )
        }

        // Quality Distribution Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = cardBorder,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Signal Quality Breakdown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                val total = validReadings.size.coerceAtLeast(1)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QualityMetricPill("Strong", strongCount, (strongCount * 100) / total, SignalStrongGreen)
                    QualityMetricPill("Fair", fairCount, (fairCount * 100) / total, SignalFairAmber)
                    QualityMetricPill("Weak / 2G", weakCount, (weakCount * 100) / total, SignalWeakRed)
                }
            }
        }

        // Carrier Comparison Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = cardBorder,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Carrier Comparison",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                listOf("Smart", "Globe", "DITO").forEach { carrier ->
                    val list = readings.filter { it.carrier.equals(carrier, ignoreCase = true) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cell_tower),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = carrier,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${list.size} readings",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (list.isEmpty()) {
                            Text(
                                text = "No data yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val avgBars = list.map { it.level }.average()
                            val dbms = list.map { it.dbm }.filter { it < 0 }
                            val avgDbmText = if (dbms.isNotEmpty()) "${dbms.average().toInt()} dBm" else "— dBm"
                            val barColor = when {
                                avgBars >= 3.0 -> SignalStrongGreen
                                avgBars >= 1.5 -> SignalFairAmber
                                else -> SignalWeakRed
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", avgBars)} / 4 bars",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = avgDbmText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SignalStrengthBars(level = avgBars.roundToInt(), activeColor = barColor)
                            }
                        }
                    }
                }
            }
        }

        // Wi-Fi Summary Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = cardBorder,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi_on),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Connected Wi-Fi Average",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${wifiReadings.size} Wi-Fi samples recorded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = if (wifiReadings.isNotEmpty()) "${wifiReadings.average().toInt()} dBm" else "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun StatKpiCard(
    title: String,
    value: String,
    accent: Color,
    border: BorderStroke,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = accent
            )
        }
    }
}

@Composable
private fun QualityMetricPill(label: String, count: Int, percent: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = color.copy(alpha = 0.14f)
        ) {
            Text(
                text = "$percent%",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$count pts",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SignalMoreScreen(
    totalReadings: Int,
    pendingOfflineCount: Int,
    syncErrorMessage: String?,
    preferPureGnss: Boolean,
    lockToBsuCampus: Boolean,
    keepScreenAwake: Boolean,
    onTogglePureGnss: (Boolean) -> Unit,
    onToggleBsuCampusLock: (Boolean) -> Unit,
    onToggleKeepScreenAwake: (Boolean) -> Unit,
    bottomPaddingDp: Dp,
    onSendTestReading: () -> Unit
) {
    val cardBorder = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = bottomPaddingDp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Interactive Recording & GPS Settings Group
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "GPS & RECORDING",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = cardBorder,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsToggleRow(
                        title = "Pure GNSS Priority",
                        subtitle = "Prefer satellite GPS over Wi-Fi/cell fixes",
                        checked = preferPureGnss,
                        onCheckedChange = onTogglePureGnss
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                    SettingsToggleRow(
                        title = "BSU Campus Lock",
                        subtitle = "Ignore GPS jumps outside campus perimeter",
                        checked = lockToBsuCampus,
                        onCheckedChange = onToggleBsuCampusLock
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                    SettingsToggleRow(
                        title = "Keep Screen Awake",
                        subtitle = "Prevent sleep while walking and recording",
                        checked = keepScreenAwake,
                        onCheckedChange = onToggleKeepScreenAwake
                    )
                }
            }
        }

        // Cloud Sync & Diagnostics Group
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "SYNC & DIAGNOSTICS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = cardBorder,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Cloud Firestore",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            val detail = when {
                                syncErrorMessage != null -> syncErrorMessage
                                pendingOfflineCount > 0 ->
                                    "$pendingOfflineCount queued offline · $totalReadings total"
                                else -> "$totalReadings readings loaded (5,000 max)"
                            }
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val statusColor = when {
                            syncErrorMessage != null -> SignalWeakRed
                            pendingOfflineCount > 0 -> SignalFairAmber
                            else -> SignalStrongGreen
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSendTestReading() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Send Test Reading",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Write a sample record to test online or Airplane Mode sync",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "SignalSpot v1.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
