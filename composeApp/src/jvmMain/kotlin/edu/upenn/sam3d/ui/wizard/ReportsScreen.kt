package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.domain.model.RunReport
import edu.upenn.sam3d.domain.model.RunStatus
import edu.upenn.sam3d.domain.model.formatDuration
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.components.CarbonStatus
import edu.upenn.sam3d.ui.components.CarbonTag
import edu.upenn.sam3d.ui.components.RunTimingBreakdown
import edu.upenn.sam3d.ui.theme.Carbon

/**
 * The Reports tab — a sibling of the wizard, reached from the header nav. Lists every recorded run
 * (newest first) with the *configuration* used (quality, slice count, downsample resolution) and the
 * measured per-stage timing + total, so the effect of toggling those levers on run time is visible
 * at a glance. Backed by `<userDataDir>/SAM3D/reports.json`; nothing here runs the pipeline.
 */
@Composable
fun ReportsScreen(state: WizardState) {
    val c = Carbon.theme
    Column(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp)
            .padding(horizontal = Carbon.spacing.spacing09, vertical = Carbon.spacing.spacing08),
        verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing06),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
            Text("Reports", style = Carbon.type.heading04, color = c.textPrimary)
            Text(
                "Every run on this machine is recorded here — the configuration used and how long each " +
                    "stage took. Use it to see how Draft vs. Production, the slice count, and the resolution " +
                    "affect run time.",
                style = Carbon.type.body01, color = c.textSecondary,
            )
        }

        if (state.reports.isEmpty()) {
            EmptyReports()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing05),
                contentPadding = PaddingValues(bottom = Carbon.spacing.spacing08),
            ) {
                items(state.reports, key = { it.id }) { report -> ReportCard(report) }
            }
        }
    }
}

@Composable
private fun EmptyReports() {
    val c = Carbon.theme
    Box(
        Modifier.fillMaxWidth().background(c.layer01).border(1.dp, c.borderSubtle01, RectangleShape)
            .padding(Carbon.spacing.spacing07),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03),
        ) {
            Text("No runs recorded yet", style = Carbon.type.headingCompact02, color = c.textPrimary)
            Text(
                "Finish a run and it'll appear here with its timing and configuration.",
                style = Carbon.type.body01, color = c.textHelper,
            )
        }
    }
}

@Composable
private fun ReportCard(report: RunReport) {
    val c = Carbon.theme
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(c.layer01).border(1.dp, c.borderSubtle01, RectangleShape)
            .padding(Carbon.spacing.spacing05),
        verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04),
    ) {
        // When it ran + how it ended + the headline total.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04)) {
                Text(report.startedAtDisplay, style = Carbon.type.headingCompact01, color = c.textPrimary)
                StatusTag(report.status)
            }
            Text(formatDuration(report.totalSeconds), style = Carbon.type.headingCompact01, color = c.textPrimary)
        }

        // The configuration this run used — the levers worth correlating with the timing below.
        Row(horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03), verticalAlignment = Alignment.CenterVertically) {
            CarbonTag(report.quality, status = CarbonStatus.INFO)
            CarbonTag("${report.slices} slices")
            CarbonTag("${report.resolutionLabel} cube")
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle01))

        RunTimingBreakdown(report)

        report.outputPath?.let { path ->
            Text(path, style = Carbon.type.code01, color = c.textHelper)
        }
    }
}

@Composable
private fun StatusTag(status: RunStatus) = when (status) {
    RunStatus.COMPLETE -> CarbonTag("Complete", status = CarbonStatus.SUCCESS, showDot = true)
    RunStatus.ERROR -> CarbonTag("Failed", status = CarbonStatus.ERROR, showDot = true)
}
