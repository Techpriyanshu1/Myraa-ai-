package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val MaterialYouDarkFallback = darkColorScheme(
    primary = Color(0xFF7AD8FF),
    onPrimary = Color(0xFF003546),
    primaryContainer = Color(0xFF004D64),
    onPrimaryContainer = Color(0xFFC3ECFF),
    secondary = Color(0xFFD0BCFF),
    onSecondary = Color(0xFF381E72),
    secondaryContainer = Color(0xFF4F378B),
    onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = Color(0xFFFFB0D0),
    onTertiary = Color(0xFF5E1138),
    tertiaryContainer = Color(0xFF7B294F),
    onTertiaryContainer = Color(0xFFFFD8E6),
    background = Color(0xFF0F141A),
    onBackground = Color(0xFFE1E6EE),
    surface = Color(0xFF0F141A),
    onSurface = Color(0xFFE1E6EE),
    surfaceVariant = Color(0xFF3D4854),
    onSurfaceVariant = Color(0xFFBFC8D4),
    surfaceContainerLowest = Color(0xFF0A0F14),
    surfaceContainerLow = Color(0xFF171C23),
    surfaceContainer = Color(0xFF1C222A),
    surfaceContainerHigh = Color(0xFF262D36),
    surfaceContainerHighest = Color(0xFF313842),
    outline = Color(0xFF88929E),
    outlineVariant = Color(0xFF3D4854),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val MaterialYouLightFallback = lightColorScheme(
    primary = Color(0xFF006584),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC3ECFF),
    onPrimaryContainer = Color(0xFF001F2A),
    secondary = Color(0xFF6750A4),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEADDFF),
    onSecondaryContainer = Color(0xFF21005D),
    tertiary = Color(0xFF984066),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E5),
    onTertiaryContainer = Color(0xFF3E0020),
    background = Color(0xFFF6FAFE),
    onBackground = Color(0xFF171C20),
    surface = Color(0xFFF6FAFE),
    onSurface = Color(0xFF171C20),
    surfaceVariant = Color(0xFFDCE4EA),
    onSurfaceVariant = Color(0xFF404850),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F8),
    surfaceContainer = Color(0xFFEAEFF4),
    surfaceContainerHigh = Color(0xFFE4E9EE),
    surfaceContainerHighest = Color(0xFFDEE4E9),
    outline = Color(0xFF707880),
    outlineVariant = Color(0xFFC0C8D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

val MyraaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MaterialYouDarkFallback
        else -> MaterialYouLightFallback
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = MyraaShapes,
        content = content
    )
}
