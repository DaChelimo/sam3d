package edu.upenn.sam3d.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.ui.theme.Carbon

enum class CarbonProgressStatus { ACTIVE, SUCCESS, ERROR }

/**
 * Carbon progress bar (https://carbondesignsystem.com/components/progress-bar/usage). Thin square
 * track, a label + right-aligned helper (e.g. a percentage), and either a determinate fill
 * ([progress] in 0..1) or an animated indeterminate sweep when [progress] is null. [status] recolours
 * the fill for the terminal success/error states.
 */
@Composable
fun CarbonProgressBar(
    modifier: Modifier = Modifier,
    label: String? = null,
    helperText: String? = null,
    progress: Float? = null,
    status: CarbonProgressStatus = CarbonProgressStatus.ACTIVE,
) {
    val c = Carbon.theme
    val track = c.layerAccent01
    val fill = when (status) {
        CarbonProgressStatus.ACTIVE -> c.interactive
        CarbonProgressStatus.SUCCESS -> c.supportSuccess
        CarbonProgressStatus.ERROR -> c.supportError
    }

    val indeterminate = progress == null && status == CarbonProgressStatus.ACTIVE
    val sweep by rememberInfiniteTransition(label = "progress").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    Column(modifier = modifier) {
        if (label != null || helperText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(label ?: "", style = Carbon.type.label01, color = c.textSecondary)
                if (helperText != null) {
                    Text(helperText, style = Carbon.type.label01, color = c.textHelper)
                }
            }
            Spacer(Modifier.height(Carbon.spacing.spacing03))
        }
        // clipToBounds so the indeterminate sweep can never paint outside the track (it animates
        // from a negative x); the determinate fill is also clamped below.
        Canvas(modifier = Modifier.fillMaxWidth().height(6.dp).clipToBounds()) {
            drawRect(color = track, size = Size(size.width, size.height))
            when {
                progress != null -> {
                    val w = (size.width * progress.coerceIn(0f, 1f))
                    if (w > 0f) drawRect(color = fill, size = Size(w, size.height))
                }
                indeterminate -> {
                    val segW = size.width * 0.3f
                    val rawX = sweep * (size.width + segW) - segW
                    val x = rawX.coerceIn(0f, size.width)               // keep the segment on-track
                    val right = (rawX + segW).coerceIn(0f, size.width)
                    if (right > x) drawRect(color = fill, topLeft = Offset(x, 0f), size = Size(right - x, size.height))
                }
                else -> { // terminal status with no explicit progress → full bar
                    drawRect(color = fill, size = Size(size.width, size.height))
                }
            }
        }
    }
}
