package edu.upenn.sam3d.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle

private val LocalCarbonColors = staticCompositionLocalOf { gray100Theme() }
private val LocalCarbonTypography = staticCompositionLocalOf { carbonTypography() }

/**
 * Ergonomic, theme-aware accessors: `Carbon.theme.layer01`, `Carbon.type.heading03`,
 * `Carbon.spacing.spacing05`. Mirrors the `MaterialTheme.colorScheme` access pattern so call sites
 * stay terse. Spacing/size are theme-invariant, so they delegate straight to the objects.
 */
object Carbon {
    val theme: CarbonColors
        @Composable @ReadOnlyComposable get() = LocalCarbonColors.current
    val type: CarbonTypography
        @Composable @ReadOnlyComposable get() = LocalCarbonTypography.current
    val spacing get() = CarbonSpacing
    val size get() = CarbonSize
}

/**
 * Root theme. Provides the Carbon CompositionLocals and also seeds a Material 3 [MaterialTheme] from
 * the same tokens — the few Material primitives still in use (Surface, Slider, AlertDialog scrim,
 * ripple) then inherit Carbon's colours and IBM Plex type instead of Material defaults.
 */
@Composable
fun CarbonTheme(
    colors: CarbonColors = gray100Theme(),
    typography: CarbonTypography = carbonTypography(),
    content: @Composable () -> Unit,
) {
    val material = darkColorScheme(
        primary = colors.buttonPrimary,
        onPrimary = colors.textOnColor,
        primaryContainer = colors.buttonPrimaryActive,
        onPrimaryContainer = colors.textOnColor,
        secondary = colors.interactive,
        onSecondary = colors.textOnColor,
        background = colors.background,
        onBackground = colors.textPrimary,
        surface = colors.layer01,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.layer02,
        onSurfaceVariant = colors.textSecondary,
        outline = colors.borderStrong01,
        outlineVariant = colors.borderSubtle01,
        error = colors.supportError,
        onError = colors.textOnColor,
        scrim = colors.overlay,
    )
    CompositionLocalProvider(
        LocalCarbonColors provides colors,
        LocalCarbonTypography provides typography,
        LocalContentColor provides colors.textPrimary,
    ) {
        MaterialTheme(
            colorScheme = material,
            typography = materialTypographyFrom(typography),
            content = content,
        )
    }
}

/** Map Carbon styles onto the Material 3 slots so stray Material `Text`s still pick up IBM Plex. */
private fun materialTypographyFrom(t: CarbonTypography): Typography {
    fun TextStyle.m() = this
    return Typography(
        displayLarge = t.fluidHeading,
        displayMedium = t.heading05,
        displaySmall = t.heading04,
        headlineLarge = t.heading05,
        headlineMedium = t.heading04,
        headlineSmall = t.heading03,
        titleLarge = t.heading03,
        titleMedium = t.heading02.m(),
        titleSmall = t.heading01,
        bodyLarge = t.body02,
        bodyMedium = t.body01,
        bodySmall = t.helperText01,
        labelLarge = t.headingCompact01,
        labelMedium = t.label02,
        labelSmall = t.label01,
    )
}
