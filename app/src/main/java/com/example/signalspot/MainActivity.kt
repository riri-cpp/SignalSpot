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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.signalspot.collector.SignalCollectorScreen
import com.example.signalspot.data.ReadingRepositoryScreen
import com.example.signalspot.map.SignalHeatmapView
import com.example.signalspot.ui.theme.SignalSpotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SignalSpotTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                val tabs = listOf("All 3", "Firebase", "Signal", "Map")

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
                                            .weight(0.40f)
                                            .fillMaxWidth()
                                    ) {
                                        SignalHeatmapView()
                                    }
                                    HorizontalDivider()
                                    Box(
                                        modifier = Modifier
                                            .weight(0.32f)
                                            .fillMaxWidth()
                                    ) {
                                        SignalCollectorScreen()
                                    }
                                    HorizontalDivider()
                                    Box(
                                        modifier = Modifier
                                            .weight(0.28f)
                                            .fillMaxWidth()
                                    ) {
                                        ReadingRepositoryScreen()
                                    }
                                }
                            }
                            1 -> ReadingRepositoryScreen(modifier = Modifier.fillMaxSize())
                            2 -> SignalCollectorScreen(modifier = Modifier.fillMaxSize())
                            3 -> SignalHeatmapView(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
