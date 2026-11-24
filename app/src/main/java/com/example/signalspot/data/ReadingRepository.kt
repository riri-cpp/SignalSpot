package com.example.signalspot.data

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import java.util.UUID
import kotlin.random.Random

class ReadingRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val readingsCollection = firestore.collection(COLLECTION_READINGS)

    /**
     * Writes a [SignalReading] to the "readings" collection using [SignalReading.id]
     * as the Firestore document ID so retries/reconnects never create duplicates.
     */
    fun saveReading(reading: SignalReading) {
        val docId = reading.id.ifBlank { UUID.randomUUID().toString() }
        val record = if (reading.id == docId && reading.gridKey.isNotBlank()) {
            reading
        } else {
            reading.copy(
                id = docId,
                gridKey = reading.gridKey.ifBlank { gridKeyFor(reading.lat, reading.lng) }
            )
        }
        readingsCollection.document(docId).set(record)
    }

    /**
     * Listens in real time to the newest 5,000 readings ordered by timestamp (newest first).
     */
    fun listenToReadings(onUpdate: (List<SignalReading>) -> Unit): ListenerRegistration {
        return listenToReadingsWithStatus { readings, _, _ ->
            onUpdate(readings)
        }
    }

    /**
     * Extended listener that also reports how many readings are currently queued offline
     * (waiting to sync to Firebase) and any Firestore rule/connection error.
     */
    fun listenToReadingsWithStatus(
        onUpdate: (readings: List<SignalReading>, pendingOfflineCount: Int, error: String?) -> Unit
    ): ListenerRegistration {
        return readingsCollection
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(MAX_READINGS_LIMIT)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    onUpdate(emptyList(), 0, error.localizedMessage ?: "Firestore error")
                    return@addSnapshotListener
                }
                if (snapshot == null) {
                    onUpdate(emptyList(), 0, null)
                    return@addSnapshotListener
                }

                var pendingCount = 0
                val list = snapshot.documents.mapNotNull { doc ->
                    if (doc.metadata.hasPendingWrites()) {
                        pendingCount++
                    }
                    doc.toObject(SignalReading::class.java)
                }
                onUpdate(list, pendingCount, null)
            }
    }

    companion object {
        private const val COLLECTION_READINGS = "readings"
        private const val MAX_READINGS_LIMIT = 5000L
    }
}

/**
 * Week 1 standalone/combined test screen for Member 3 (ReadingRepository.kt).
 */
@Composable
fun ReadingRepositoryScreen(
    modifier: Modifier = Modifier,
    repository: ReadingRepository = remember { ReadingRepository() }
) {
    var readings by remember { mutableStateOf<List<SignalReading>>(emptyList()) }
    var pendingOfflineCount by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sentCounter by remember { mutableIntStateOf(0) }

    DisposableEffect(repository) {
        val registration = repository.listenToReadingsWithStatus { updatedList, pendingCount, error ->
            readings = updatedList
            pendingOfflineCount = pendingCount
            errorMessage = error
        }
        onDispose {
            registration.remove()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SignalSpot — Firebase Repository",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            val statusText = when {
                errorMessage != null -> "Error: $errorMessage\n(Check Firestore Rules in Console)"
                pendingOfflineCount > 0 ->
                    "Total loaded: ${readings.size} | Offline Queued: $pendingOfflineCount"
                else -> "Total loaded: ${readings.size} | All synced to Cloud"
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            val latest = readings.firstOrNull()
            if (latest != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Latest: ${latest.carrier} ${latest.networkType} (${latest.dbm} dBm, ${latest.level} bars)\nID: ${latest.id.take(8)}...",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val carriers = listOf("Smart", "Globe", "DITO")
                    val carrier = carriers[sentCounter % carriers.size]
                    val level = Random.nextInt(1, 5) // 1..4 bars (valid per Firestore rules 0..4)
                    val dbm = -110 + (level * 12)
                    val lat = 13.7847 + Random.nextDouble(-0.0004, 0.0004)
                    val lng = 121.0735 + Random.nextDouble(-0.0004, 0.0004)

                    val fakeReading = SignalReading(
                        id = UUID.randomUUID().toString(),
                        lat = lat,
                        lng = lng,
                        gridKey = gridKeyFor(lat, lng),
                        accuracy = 8.5f,
                        carrier = carrier,
                        networkType = "4G",
                        dbm = dbm,
                        level = level,
                        wifiDbm = -62,
                        timestamp = System.currentTimeMillis()
                    )
                    repository.saveReading(fakeReading)
                    sentCounter++
                }
            ) {
                Text(text = "Send test reading")
            }
        }
    }
}
