package com.example.blockingapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF1F3A5F),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFB4C5E0),
    tertiary = Pink80,
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE3E6EB),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE3E6EB),
    surfaceVariant = Color(0xFF232A33),
    onSurfaceVariant = Color(0xFFB5BDC9),
    surfaceContainerLowest = Color(0xFF0A0D11),
    surfaceContainerLow = Color(0xFF151A21),
    surfaceContainer = Color(0xFF1A2028),
    surfaceContainerHigh = Color(0xFF202731),
    surfaceContainerHighest = Color(0xFF272F3A),
    outline = Color(0xFF6B7684),
    outlineVariant = Color(0xFF3A444F),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A06)
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun BlockingAppTheme(
    darkTheme: Boolean = true,
    // Off by default so the app keeps its own dark palette instead of the wallpaper colors.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}