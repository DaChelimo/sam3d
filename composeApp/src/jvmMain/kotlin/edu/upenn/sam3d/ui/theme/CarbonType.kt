package edu.upenn.sam3d.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/**
 * IBM Plex — Carbon's typeface — loaded from the bundled TTFs in `jvmMain/resources/fonts/`. Loading
 * is defensive: if a weight is missing from the classpath we fall back to the platform sans/mono so
 * the app still renders rather than crashing during text layout. (The TTFs ARE committed; this guard
 * just makes the theme robust to a stripped build.)
 */
private fun resourceExists(path: String): Boolean =
    CarbonTypography::class.java.getResource("/$path") != null

val PlexSans: FontFamily = runCatching {
    if (!resourceExists("fonts/IBMPlexSans-Regular.ttf")) FontFamily.SansSerif
    else FontFamily(
        Font("fonts/IBMPlexSans-Light.ttf", FontWeight.Light),
        Font("fonts/IBMPlexSans-Regular.ttf", FontWeight.Normal),
        Font("fonts/IBMPlexSans-Medium.ttf", FontWeight.Medium),
        Font("fonts/IBMPlexSans-SemiBold.ttf", FontWeight.SemiBold),
    )
}.getOrDefault(FontFamily.SansSerif)

val PlexMono: FontFamily = runCatching {
    if (!resourceExists("fonts/IBMPlexMono-Regular.ttf")) FontFamily.Monospace
    else FontFamily(
        Font("fonts/IBMPlexMono-Regular.ttf", FontWeight.Normal),
        Font("fonts/IBMPlexMono-Medium.ttf", FontWeight.Medium),
    )
}.getOrDefault(FontFamily.Monospace)

/**
 * Carbon's **productive** type set plus the larger headings used for screen titles
 * (https://carbondesignsystem.com/elements/typography/type-sets). Token names match the spec:
 * `$body-01` → [body01], `$heading-compact-01` → [headingCompact01], etc.
 */
@Immutable
data class CarbonTypography(
    val code01: TextStyle,
    val code02: TextStyle,
    val label01: TextStyle,
    val label02: TextStyle,
    val helperText01: TextStyle,
    val helperText02: TextStyle,
    val bodyCompact01: TextStyle,
    val bodyCompact02: TextStyle,
    val body01: TextStyle,
    val body02: TextStyle,
    val headingCompact01: TextStyle,
    val headingCompact02: TextStyle,
    val heading01: TextStyle,
    val heading02: TextStyle,
    val heading03: TextStyle,
    val heading04: TextStyle,
    val heading05: TextStyle,
    val fluidHeading: TextStyle,
)

fun carbonTypography(sans: FontFamily = PlexSans, mono: FontFamily = PlexMono): CarbonTypography {
    return CarbonTypography(
        code01 = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.32.sp),
        code02 = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.32.sp),
        label01 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.32.sp),
        label02 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.16.sp),
        helperText01 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.32.sp),
        helperText02 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.16.sp),
        bodyCompact01 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.16.sp),
        bodyCompact02 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
        body01 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.16.sp),
        body02 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        headingCompact01 = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.16.sp),
        headingCompact02 = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        heading01 = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.16.sp),
        heading02 = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        heading03 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 28.sp),
        heading04 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp),
        heading05 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 40.sp),
        fluidHeading = TextStyle(fontFamily = sans, fontWeight = FontWeight.Light, fontSize = 42.sp, lineHeight = 50.sp),
    )
}
