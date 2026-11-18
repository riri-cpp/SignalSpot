package com.example.signalspot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.signalspot.collector.SignalCollectorScreen
import com.example.signalspot.data.ReadingRepository
import com.example.signalspot.data.ReadingRepositoryScreen
import com.example.signalspot.data.SignalReading
import com.example.signalspot.map.SignalHeatmapView
import com.example.signalspot.ui.theme.SignalSpotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SignalSpotTheme {
                val repository = remember { ReadingRepository() }
                var readings by remember { mutableStateOf<List<SignalReading>>(emptyList()) }
                var pendingOfflineCount by remember { mutableIntStateOf(0) }
                var userLat by remember { mutableStateOf<Double?>(null) }
                var userLng by remember { mutableStateOf<Double?>(null) }

                var selectedTab by remember { mutableIntStateOf(0) }
                val tabs = listOf("All 3", "Firebase", "Signal", "Map")

                DisposableEffect(repository) {
                    val registration = repository.listenToReadingsWithStatus { list, pending, _ ->
                        readings = list
                        pendingOfflineCount = pending
                    }
                    onDispose {
                        registration.remove()
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        PrimaryTabRow(selectedTabIndex = selectedTab) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(title) }
                                )
                            }
                        }

                        when (selectedTab) {
                            0 -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Box(
                                        modifier = Modifier
                                            .weight(0.44f)
                                            .fillMaxWidth()
                                    ) {
                                        SignalHeatmapView(
                                            readings = readings,
                                            userLat = userLat,
                                            userLng = userLng
                                        )
                                    }
                                    HorizontalDivider()
                                    Box(
                                        modifier = Modifier
                                            .weight(0.30f)
                                            .fillMaxWidth()
                                    ) {
                                        SignalCollectorScreen(
                                            pendingOfflineCount = pendingOfflineCount,
                                            onReadingCaptured = { reading ->
                                                repository.saveReading(reading)
                                            },
                                            onLocationUpdated = { lat, lng, _ ->
                                                userLat = lat
                                                userLng = lng
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    Box(
                                        modifier = Modifier
                                            .weight(0.26f)
                                            .fillMaxWidth()
                                    ) {
                                        ReadingRepositoryScreen(repository = repository)
                                    }
                                }
                            }
                            1 -> ReadingRepositoryScreen(
                                modifier = Modifier.fillMaxSize(),
                                repository = repository
                            )
                            2 -> SignalCollectorScreen(
                                modifier = Modifier.fillMaxSize(),
                                pendingOfflineCount = pendingOfflineCount,
                                onReadingCaptured = { reading ->
                                    repository.saveReading(reading)
                                },
                                onLocationUpdated = { lat, lng, _ ->
                                    userLat = lat
                                    userLng = lng
                                }
                            )
                            3 -> SignalHeatmapView(
                                modifier = Modifier.fillMaxSize(),
                                readings = readings,
                                userLat = userLat,
                                userLng = userLng
                            )
                        }
                    }
                }
            }
        }
    }
}
