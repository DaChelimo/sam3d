package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.ui.theme.Carbon

enum class CarbonStepStatus { COMPLETE, CURRENT, INCOMPLETE, ERROR }

data class CarbonStep(val label: String, val caption: String? = null)

/**
 * Carbon vertical progress indicator (https://carbondesignsystem.com/components/progress-indicator).
 * Each step shows a status glyph connected by a rule: complete steps read in the interactive accent
 * (ring + check), the current step is a bold filled target, upcoming steps are quiet outlines. Used
 * as the wizard's left rail. Steps marked [CarbonStepStatus.COMPLETE] are clickable for back-nav.
 */
@Composable
fun CarbonProgressIndicator(
    steps: List<CarbonStep>,
    statuses: List<CarbonStepStatus>,
    modifier: Modifier = Modifier,
    connectorHeight: Dp = 28.dp,
    onStepClick: ((Int) -> Unit)? = null,
) {
    val c = Carbon.theme
    Column(modifier = modifier) {
        steps.forEachIndexed { i, step ->
            val status = statuses.getOrElse(i) { CarbonStepStatus.INCOMPLETE }
            val isLast = i == steps.lastIndex
            val clickable = onStepClick != null && status == CarbonStepStatus.COMPLETE

            Row(
                modifier = Modifier
                    .then(
                        if (clickable) Modifier
                            .clickable { onStepClick!!(i) }
                            .pointerHoverIcon(PointerIcon.Hand)
                        else Modifier
                    ),
                verticalAlignment = Alignment.Top,
            ) {
                // Indicator column: glyph then a FIXED-height connector rule down to the next glyph.
                // (A weighted rule would greedily consume all available height — see git history.)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(20.dp),
                ) {
                    StepGlyph(status)
                    if (!isLast) {
                        val rule = if (status == CarbonStepStatus.COMPLETE) c.interactive else c.borderSubtle02
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.width(1.dp).height(connectorHeight).background(rule))
                    }
                }
                Spacer(Modifier.width(Carbon.spacing.spacing04))
                Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else Carbon.spacing.spacing05)) {
                    Text(
                        step.label,
                        style = if (status == CarbonStepStatus.CURRENT) Carbon.type.headingCompact01 else Carbon.type.bodyCompact01,
                        color = when (status) {
                            CarbonStepStatus.CURRENT -> c.textPrimary
                            CarbonStepStatus.COMPLETE -> c.textPrimary
                            CarbonStepStatus.ERROR -> c.textError
                            CarbonStepStatus.INCOMPLETE -> c.textHelper
                        },
                    )
                    if (step.caption != null) {
                        Text(step.caption, style = Carbon.type.label01, color = c.textHelper)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepGlyph(status: CarbonStepStatus) {
    val c = Carbon.theme
    when (status) {
        CarbonStepStatus.ERROR -> CarbonStatusGlyph(
            CarbonStatus.ERROR, c.supportError, c.background, modifier = Modifier.size(20.dp),
        )
        else -> Canvas(modifier = Modifier.size(20.dp)) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            when (status) {
                CarbonStepStatus.COMPLETE -> {
                    drawCircle(c.interactive, radius = r - 1f, center = center, style = Stroke(1.4f))
                    val p = Path().apply {
                        moveTo(center.x - r * 0.42f, center.y + r * 0.02f)
                        lineTo(center.x - r * 0.10f, center.y + r * 0.34f)
                        lineTo(center.x + r * 0.46f, center.y - r * 0.34f)
                    }
                    drawPath(p, c.interactive, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                CarbonStepStatus.CURRENT -> {
                    drawCircle(c.interactive, radius = r - 1f, center = center, style = Stroke(1.4f))
                    drawCircle(c.interactive, radius = r * 0.42f, center = center)
                }
                else -> drawCircle(c.borderStrong01, radius = r - 1f, center = center, style = Stroke(1.4f))
            }
        }
    }
}
