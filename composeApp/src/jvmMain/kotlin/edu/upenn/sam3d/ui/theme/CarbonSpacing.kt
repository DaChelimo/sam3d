package edu.upenn.sam3d.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Carbon's spacing scale (https://carbondesignsystem.com/elements/spacing/overview). A small, fixed
 * set of step tokens built on a 2px base — using these instead of arbitrary dp values is what keeps
 * the layout rhythm consistent. Named `spacingNN` to match the spec (`$spacing-05` → [spacing05]).
 */
object CarbonSpacing {
    val spacing01: Dp = 2.dp
    val spacing02: Dp = 4.dp
    val spacing03: Dp = 8.dp
    val spacing04: Dp = 12.dp
    val spacing05: Dp = 16.dp
    val spacing06: Dp = 24.dp
    val spacing07: Dp = 32.dp
    val spacing08: Dp = 40.dp
    val spacing09: Dp = 48.dp
    val spacing10: Dp = 64.dp
    val spacing11: Dp = 80.dp
    val spacing12: Dp = 96.dp
    val spacing13: Dp = 160.dp
}

/**
 * Carbon component sizing constants. Heights follow Carbon's field/button size ramp; Carbon notably
 * uses **0px corner radius** everywhere except tags — [radiusNone] documents that intent at call
 * sites so it reads as a deliberate choice, not an omission.
 */
object CarbonSize {
    val fieldHeightSm: Dp = 32.dp   // "small"
    val fieldHeightMd: Dp = 40.dp   // "medium"
    val fieldHeightLg: Dp = 48.dp   // "large" — Carbon default for buttons & fields
    val fieldHeightXl: Dp = 64.dp   // "extra large"

    val radiusNone: Dp = 0.dp       // Carbon corners are square
    val radiusTag: Dp = 12.dp       // the one exception: tags are pill-rounded

    val borderWidth: Dp = 1.dp
    val borderStrongWidth: Dp = 2.dp
    val focusWidth: Dp = 2.dp

    val railWidth: Dp = 232.dp      // wizard progress rail
    val iconSm: Dp = 16.dp
    val iconMd: Dp = 20.dp
    val iconLg: Dp = 24.dp
}
