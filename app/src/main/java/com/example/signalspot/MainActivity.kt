package com.example.signalspot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.signalspot.collector.SignalCollectorScreen
import com.example.signalspot.ui.theme.SignalSpotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SignalSpotTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SignalCollectorScreen(
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}