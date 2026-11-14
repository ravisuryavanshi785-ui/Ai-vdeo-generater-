package com.rsmoviespot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val RsMovieSpotDarkColors = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color(0xFF062000),
    secondary = NeonGreenDim,
    onSecondary = Color(0xFF002117),
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color.Black,
    outline = GlassBorder
)

@Composable
fun RsMovieSpotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep the app's chosen neon palette instead of Android dynamic colors.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        else -> RsMovieSpotDarkColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}