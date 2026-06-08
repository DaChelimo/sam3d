package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.domain.model.RunReport
import edu.upenn.sam3d.domain.model.formatDuration
import edu.upenn.sam3d.ui.theme.Carbon

/**
 * The per-stage timing list for one run — each stage on its own line with its measured duration on
 * the right, and a bold **Total** at the very bottom. Shared by the Done screen (the run that just
 * finished) and the Reports tab (any past run), so the layout reads identically in both places.
 */
@Composable
fun RunTimingBreakdown(report: RunReport, modifier: Modifier = Modifier) {
    val c = Carbon.theme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
        report.stages.forEach { stage ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stage.label, style = Carbon.type.body01, color = c.textSecondary)
                Text(formatDuration(stage.seconds), style = Carbon.type.code01, color = c.textPrimary)
            }
        }
        if (report.stages.isEmpty()) {
            Text("No per-stage timing was captured for this run.", style = Carbon.type.helperText01, color = c.textHelper)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle01))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total", style = Carbon.type.headingCompact01, color = c.textPrimary)
            Text(formatDuration(report.totalSeconds), style = Carbon.type.headingCompact01, color = c.textPrimary)
        }
    }
}
