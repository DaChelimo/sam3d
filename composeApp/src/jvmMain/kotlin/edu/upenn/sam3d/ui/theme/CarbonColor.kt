package edu.upenn.sam3d.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * IBM Carbon Design System colour tokens.
 *
 * Two layers, exactly as Carbon defines them:
 *  - [CarbonPalette] — the raw, theme-agnostic swatches (gray10…gray100, blue60, …). Never used
 *    directly in UI; tokens reference these so a value lives in one place.
 *  - [CarbonColors] — the *semantic* token set for one theme. We ship the **Gray 100** (dark) theme,
 *    Carbon's default dark canvas, which suits a medical-imaging tool: a near-black background lets
 *    the grayscale DICOM slices read accurately without surrounding light spill.
 *
 * Reference: https://carbondesignsystem.com/elements/color/tokens (Gray 100 column).
 */
object CarbonPalette {
    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF000000)

    val gray10 = Color(0xFFF4F4F4)
    val gray20 = Color(0xFFE0E0E0)
    val gray30 = Color(0xFFC6C6C6)
    val gray40 = Color(0xFFA8A8A8)
    val gray50 = Color(0xFF8D8D8D)
    val gray60 = Color(0xFF6F6F6F)
    val gray70 = Color(0xFF525252)
    val gray80 = Color(0xFF393939)
    val gray90 = Color(0xFF262626)
    val gray100 = Color(0xFF161616)

    val blue40 = Color(0xFF78A9FF)
    val blue50 = Color(0xFF4589FF)
    val blue60 = Color(0xFF0F62FE)
    val blue70 = Color(0xFF0043CE)
    val blue80 = Color(0xFF002D9C)

    val red30 = Color(0xFFFFB3B8)
    val red40 = Color(0xFFFF8389)
    val red50 = Color(0xFFFA4D56)
    val red60 = Color(0xFFDA1E28)
    val red80 = Color(0xFF750E13)

    val green30 = Color(0xFF6FDC8C)
    val green40 = Color(0xFF42BE65)
    val green50 = Color(0xFF24A148)
    val green60 = Color(0xFF198038)

    val yellow30 = Color(0xFFF1C21B)
    val orange40 = Color(0xFFFF832B)
}

/**
 * One theme's worth of semantic tokens. Field names mirror Carbon's token names (camelCased) so they
 * map 1:1 to the published spec; e.g. `layer01` is Carbon `$layer-01`.
 */
@Immutable
data class CarbonColors(
    val isDark: Boolean,
    // Backgrounds & layers
    val background: Color,
    val backgroundHover: Color,
    val backgroundActive: Color,
    val backgroundInverse: Color,
    val layer01: Color,
    val layer02: Color,
    val layer03: Color,
    val layerHover01: Color,
    val layerActive01: Color,
    val layerAccent01: Color,
    val layerSelectedInverse: Color,
    // Fields
    val field01: Color,
    val field02: Color,
    val fieldHover01: Color,
    // Borders
    val borderSubtle01: Color,
    val borderSubtle02: Color,
    val borderStrong01: Color,
    val borderInverse: Color,
    val borderInteractive: Color,
    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textPlaceholder: Color,
    val textHelper: Color,
    val textOnColor: Color,
    val textDisabled: Color,
    val textError: Color,
    // Links & icons
    val linkPrimary: Color,
    val linkPrimaryHover: Color,
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconOnColor: Color,
    val iconDisabled: Color,
    // Interactive accent
    val interactive: Color,
    // Buttons
    val buttonPrimary: Color,
    val buttonPrimaryHover: Color,
    val buttonPrimaryActive: Color,
    val buttonSecondary: Color,
    val buttonSecondaryHover: Color,
    val buttonSecondaryActive: Color,
    val buttonDanger: Color,
    val buttonDangerHover: Color,
    val buttonDangerActive: Color,
    val buttonDisabled: Color,
    // Support / status
    val supportError: Color,
    val supportSuccess: Color,
    val supportWarning: Color,
    val supportInfo: Color,
    // Focus & overlay
    val focus: Color,
    val focusInset: Color,
    val overlay: Color,
    // Skeleton
    val skeletonBackground: Color,
    val skeletonElement: Color,
    // Domain accents (kept Carbon-consistent): green = positive prompt, red = negative prompt.
    val annotationPositive: Color,
    val annotationNegative: Color,
)

/** Carbon **Gray 100** (dark) theme — the app's single theme. */
fun gray100Theme(): CarbonColors = with(CarbonPalette) {
    CarbonColors(
        isDark = true,
        background = gray100,
        backgroundHover = Color(0xFF2C2C2C),
        backgroundActive = Color(0xFF393939),
        backgroundInverse = gray10,
        layer01 = gray90,
        layer02 = gray80,
        layer03 = gray70,
        layerHover01 = Color(0xFF333333),
        layerActive01 = gray70,
        layerAccent01 = gray80,
        layerSelectedInverse = gray10,
        field01 = gray90,
        field02 = gray80,
        fieldHover01 = Color(0xFF333333),
        borderSubtle01 = gray80,
        borderSubtle02 = gray70,
        borderStrong01 = gray60,
        borderInverse = gray10,
        borderInteractive = blue50,
        textPrimary = gray10,
        textSecondary = gray30,
        textPlaceholder = gray60,
        textHelper = gray50,
        textOnColor = white,
        textDisabled = gray70,
        textError = red40,
        linkPrimary = blue40,
        linkPrimaryHover = Color(0xFFA6C8FF),
        iconPrimary = gray10,
        iconSecondary = gray30,
        iconOnColor = white,
        iconDisabled = gray70,
        interactive = blue50,
        buttonPrimary = blue60,
        buttonPrimaryHover = Color(0xFF0353E9),
        buttonPrimaryActive = blue80,
        buttonSecondary = gray60,
        buttonSecondaryHover = Color(0xFF606060),
        buttonSecondaryActive = gray80,
        buttonDanger = red60,
        buttonDangerHover = Color(0xFFBA1B23),
        buttonDangerActive = red80,
        buttonDisabled = gray80,
        supportError = red50,
        supportSuccess = green40,
        supportWarning = yellow30,
        supportInfo = blue50,
        focus = white,
        focusInset = gray100,
        overlay = Color(0xA6000000), // black @ 65%
        skeletonBackground = Color(0xFF333333),
        skeletonElement = gray70,
        annotationPositive = green40,
        annotationNegative = red50,
    )
}
