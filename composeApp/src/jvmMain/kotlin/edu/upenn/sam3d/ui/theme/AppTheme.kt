package edu.upenn.sam3d.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val darkScheme = darkColorScheme(
    primary = Color(0xFF90CAF9.toInt()),
    onPrimary = Color(0xFF003258.toInt()),
    primaryContainer = Color(0xFF004880.toInt()),
    onPrimaryContainer = Color(0xFFD1E4FF.toInt()),
    secondary = Color(0xFFB0BEC5.toInt()),
    onSecondary = Color(0xFF1A2327.toInt()),
    secondaryContainer = Color(0xFF2C3E50.toInt()),
    onSecondaryContainer = Color(0xFFCFE8F3.toInt()),
    background = Color(0xFF121212.toInt()),
    onBackground = Color(0xFFE0E0E0.toInt()),
    surface = Color(0xFF1E1E1E.toInt()),
    onSurface = Color(0xFFE0E0E0.toInt()),
    surfaceVariant = Color(0xFF2C2C2C.toInt()),
    onSurfaceVariant = Color(0xFFBBBBBB.toInt()),
    outline = Color(0xFF555555.toInt()),
    error = Color(0xFFCF6679.toInt()),
    onError = Color(0xFF690020.toInt()),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkScheme,
        content = content,
    )
}
