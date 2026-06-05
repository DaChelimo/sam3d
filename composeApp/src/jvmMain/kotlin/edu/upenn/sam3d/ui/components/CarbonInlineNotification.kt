package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.ui.theme.Carbon

/**
 * Carbon inline notification (https://carbondesignsystem.com/components/notification/usage): a
 * status-coloured left bar, a status glyph, a bold title with optional subtitle, an optional slot for
 * extra content (e.g. a scrollable log), and an optional close affordance. Square, on `$layer-01`.
 */
@Composable
fun CarbonInlineNotification(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    status: CarbonStatus = CarbonStatus.ERROR,
    onClose: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val c = Carbon.theme
    val accent: Color = when (status) {
        CarbonStatus.ERROR -> c.supportError
        CarbonStatus.SUCCESS -> c.supportSuccess
        CarbonStatus.WARNING -> c.supportWarning
        CarbonStatus.INFO -> c.supportInfo
    }
    Box(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .background(c.layer01, RectangleShape)
            .border(1.dp, c.borderSubtle01, RectangleShape),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            CarbonStatusGlyph(
                status = status, color = accent, knockout = c.layer01,
                modifier = Modifier
                    .padding(start = Carbon.spacing.spacing05, top = Carbon.spacing.spacing05)
                    .size(Carbon.size.iconMd),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Carbon.spacing.spacing04, top = Carbon.spacing.spacing05, bottom = Carbon.spacing.spacing05, end = Carbon.spacing.spacing05),
            ) {
                Text(title, style = Carbon.type.headingCompact01, color = c.textPrimary)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = Carbon.type.body01, color = c.textSecondary)
                }
                if (content != null) {
                    Spacer(Modifier.height(Carbon.spacing.spacing04))
                    content()
                }
            }
            if (onClose != null) {
                Icon(
                    CarbonIcons.Close,
                    contentDescription = "Dismiss",
                    tint = c.iconPrimary,
                    modifier = Modifier
                        .padding(Carbon.spacing.spacing04)
                        .size(Carbon.size.iconMd)
                        .clickable { onClose() }
                        .pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}
