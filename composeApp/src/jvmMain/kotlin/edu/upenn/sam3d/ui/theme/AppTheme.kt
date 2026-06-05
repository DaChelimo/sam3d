package edu.upenn.sam3d.ui.theme

import androidx.compose.runtime.Composable

/**
 * App theme entry point. Thin alias over [CarbonTheme] (IBM Carbon Design System, Gray 100 dark) so
 * the rest of the app keeps calling `AppTheme { … }`.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    CarbonTheme(content = content)
}
