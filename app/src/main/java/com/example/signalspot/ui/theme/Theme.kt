package com.example.signalspot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BrandTealDark,
    onPrimary = Color(0xFF042228),
    primaryContainer = BrandTealContainerDark,
    onPrimaryContainer = BrandOnTealContainerDark,
    secondary = BrandSlateDark,
    onSecondary = Color(0xFF071B30),
    secondaryContainer = Color(0xFF1B334D),
    onSecondaryContainer = Color(0xFFE5F0F8),
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLowest = Color(0xFF071321),
    surfaceContainerLow = Color(0xFF0E2034),
    surfaceContainer = Color(0xFF13273D),
    surfaceContainerHigh = Color(0xFF1A314A),
    surfaceContainerHighest = Color(0xFF233C58),
    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = Color(0xFF072540),
    outline = Color(0xFF3E5670),
    outlineVariant = Color(0xFF243B54),
)

private val LightColorScheme = lightColorScheme(
    primary = BrandTealLight,
    onPrimary = Color.White,
    primaryContainer = BrandTealContainerLight,
    onPrimaryContainer = BrandOnTealContainerLight,
    secondary = BrandSlateLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF2F6),
    onSecondaryContainer = BrandNavy,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7FAFC),
    surfaceContainer = Color(0xFFF0F5F8),
    surfaceContainerHigh = Color(0xFFE5EEF3),
    surfaceContainerHighest = Color(0xFFD6E3EB),
    inverseSurface = BrandNavBarNavy,
    inverseOnSurface = Color(0xFFF8FAFC),
    outline = Color(0xFFB5C6D4),
    outlineVariant = Color(0xFFE2ECF2),
)

@Composable
fun SignalSpotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
