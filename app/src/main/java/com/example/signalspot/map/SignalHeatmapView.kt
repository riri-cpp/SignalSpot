package com.example.signalspot.map

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.signalspot.data.GRID_SIZE
import kotlin.math.floor
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

// Approximate center of BatStateU Alangilan. Verify with Google Maps
// (long-press the campus and copy the numbers).
private const val CAMPUS_LAT = 13.7847
private const val CAMPUS_LON = 121.0735

private enum class SignalLevel(val fillColor: Int) {
    GOOD(Color.argb(110, 0, 200, 90)),     // green
    FAIR(Color.argb(110, 255, 170, 0)),    // orange
    POOR(Color.argb(110, 230, 30, 30))     // red
}

private data class FakeSquare(
    val latOffset: Int,   // grid steps north (+) / south (-) of center
    val lonOffset: Int,   // grid steps east (+) / west (-) of center
    val level: SignalLevel
)

private val fakeSquares = listOf(
    FakeSquare(0, 0, SignalLevel.GOOD),
    FakeSquare(0, 1, SignalLevel.GOOD),
    FakeSquare(1, 0, SignalLevel.FAIR),
    FakeSquare(1, 1, SignalLevel.POOR),
    FakeSquare(-1, 0, SignalLevel.FAIR),
    FakeSquare(-1, -1, SignalLevel.POOR),
    FakeSquare(0, -1, SignalLevel.GOOD),
    FakeSquare(-1, 1, SignalLevel.FAIR)
)

private fun buildSquarePolygon(square: FakeSquare): Polygon {
    // Snap the campus center to the shared grid so squares line up
    // with the squares that gridKeyFor() produces.
    val baseRow = floor(CAMPUS_LAT / GRID_SIZE)
    val baseCol = floor(CAMPUS_LON / GRID_SIZE)

    val south = (baseRow + square.latOffset) * GRID_SIZE
    val west = (baseCol + square.lonOffset) * GRID_SIZE
    val north = south + GRID_SIZE
    val east = west + GRID_SIZE

    return Polygon().apply {
        points = listOf(
            GeoPoint(south, west),
            GeoPoint(south, east),
            GeoPoint(north, east),
            GeoPoint(north, west)
        )
        fillPaint.color = square.level.fillColor
        outlinePaint.color = Color.argb(160, 0, 0, 0)
        outlinePaint.strokeWidth = 2f
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
fun SignalHeatmapView(modifier: Modifier = Modifier) {
    val context = LocalContext.current

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
            fakeSquares.forEach { overlays.add(buildSquarePolygon(it)) }
        }
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

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize()
    )
}