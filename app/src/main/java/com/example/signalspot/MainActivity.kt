package com.example.signalspot

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.signalspot.collector.SignalCollectorBottomCard
import com.example.signalspot.data.ReadingRepository
import com.example.signalspot.data.SignalReading
import com.example.signalspot.data.gridKeyFor
import com.example.signalspot.map.SignalHeatmapView
import com.example.signalspot.ui.theme.SignalSpotTheme
import java.util.UUID
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen awake while SignalSpot is open in foreground during campus walks
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            SignalSpotTheme {
                val repository = remember { ReadingRepository() }
                var readings by remember { mutableStateOf<List<SignalReading>>(emptyList()) }
                var pendingOfflineCount by remember { mutableIntStateOf(0) }
                var syncErrorMessage by remember { mutableStateOf<String?>(null) }
                var userLat by remember { mutableStateOf<Double?>(null) }
                var userLng by remember { mutableStateOf<Double?>(null) }
                var testCounter by remember { mutableIntStateOf(0) }

                // Dynamically tracks bottom card height so map legend, pills & popups never overlap
                var bottomCardHeightPx by remember { mutableIntStateOf(0) }
                val bottomCardHeightDp = with(LocalDensity.current) { bottomCardHeightPx.toDp() }

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

                Box(modifier = Modifier.fillMaxSize()) {
                    // Full-screen interactive OpenStreetMap heatmap with floating filters & controls
                    SignalHeatmapView(
                        modifier = Modifier.fillMaxSize(),
                        readings = readings,
                        pendingOfflineCount = pendingOfflineCount,
                        syncErrorMessage = syncErrorMessage,
                        userLat = userLat,
                        userLng = userLng,
                        bottomControlsPadding = bottomCardHeightDp + 6.dp
                    )

                    // Sleek floating bottom Live Signal & Auto-Recorder card
                    SignalCollectorBottomCard(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .onGloballyPositioned { coords ->
                                bottomCardHeightPx = coords.size.height
                            },
                        pendingOfflineCount = pendingOfflineCount,
                        onReadingCaptured = { reading ->
                            repository.saveReading(reading)
                        },
                        onLocationUpdated = { lat, lng, _ ->
                            userLat = lat
                            userLng = lng
                        },
                        onSendTestReading = {
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
                    )
                }
            }
        }
    }
}
