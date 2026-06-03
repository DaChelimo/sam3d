package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.ui.theme.Carbon

/**
 * Carbon tag (https://carbondesignsystem.com/components/tag/usage) — Carbon's one pill-shaped
 * component. Used here for compact status chips (Python ready, checkpoint found, …). Colour pairs
 * follow Carbon's dark-theme tag tints (dark fill, light-on-dark label) for legibility.
 */
@Composable
fun CarbonTag(
    text: String,
    modifier: Modifier = Modifier,
    status: CarbonStatus? = null,
    showDot: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = Carbon.theme
    val (bg, fg) = when (status) {
        CarbonStatus.SUCCESS -> Color(0xFF0E6027) to Color(0xFFA7F0BA)
        CarbonStatus.ERROR -> Color(0xFFA2191F) to Color(0xFFFFD7D9)
        CarbonStatus.WARNING -> Color(0xFF684E00) to Color(0xFFFADC6F)
        CarbonStatus.INFO -> Color(0xFF0043CE) to Color(0xFFD0E2FF)
        null -> c.layer02 to c.textSecondary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Carbon.size.radiusTag))
            .background(bg)
            .padding(horizontal = Carbon.spacing.spacing03, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Canvas(Modifier.size(6.dp)) { drawCircle(fg) }
            Spacer(Modifier.width(Carbon.spacing.spacing03))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(Carbon.size.iconSm))
            Spacer(Modifier.width(Carbon.spacing.spacing02))
        }
        Text(text, style = Carbon.type.label01, color = fg)
    }
}
