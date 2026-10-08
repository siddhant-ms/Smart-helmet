package com.example.smarthelmet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Shelmet uses a dark, fixed visual language across the entire app.
 *
 * Dynamic Material colors are intentionally not used here because they
 * can change the appearance of Material components based on the user's
 * wallpaper/device color configuration.
 */
private val ShelmetDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7ED4E0),
    onPrimary = Color(0xFF061014),

    primaryContainer = Color(0xFF16363C),
    onPrimaryContainer = Color(0xFFB7F3FA),

    secondary = Color(0xFFA3B18A),
    onSecondary = Color(0xFF182016),

    secondaryContainer = Color(0xFF35412F),
    onSecondaryContainer = Color(0xFFD1DFC0),

    tertiary = Color(0xFFC24934),
    onTertiary = Color.White,

    background = Color(0xFF090909),
    onBackground = Color.White,

    surface = Color(0xFF101010),
    onSurface = Color.White,

    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFB8B8B8),

    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF2A2A2A),

    error = Color(0xFFFF5449),
    onError = Color.White,

    errorContainer = Color(0xFF5C1512),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun SmartHelmetTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    /*
     * darkTheme and dynamicColor remain as parameters so existing
     * calls such as SmartHelmetTheme { ... } continue to compile.
     *
     * Shelmet intentionally always uses the fixed dark scheme.
     */
    MaterialTheme(
        colorScheme = ShelmetDarkColorScheme,
        typography = Typography,
        content = content
    )
}
