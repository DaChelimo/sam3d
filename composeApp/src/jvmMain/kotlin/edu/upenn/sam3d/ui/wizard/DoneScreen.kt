package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.OsUtils
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.components.CarbonButton
import edu.upenn.sam3d.ui.components.CarbonButtonVariant
import edu.upenn.sam3d.ui.components.CarbonIcons
import edu.upenn.sam3d.ui.components.CarbonStatus
import edu.upenn.sam3d.ui.components.CarbonStatusGlyph
import edu.upenn.sam3d.ui.components.RunTimingBreakdown
import edu.upenn.sam3d.ui.theme.Carbon
import kotlin.io.path.Path

/** §5.7 — output path, OS-specific reveal, Start Over, processing summary. Owns its own actions (the
 *  shell footer is hidden on Done). */
@Composable
fun DoneScreen(state: WizardState, onIntent: (WizardIntent) -> Unit) {
    val c = Carbon.theme
    val outputPath = state.outputGcodePath
    val annotatedSlices = state.annotations.count { a ->
        a.positivePolylines.any { it.isNotEmpty() } || a.negativePolylines.any { it.isNotEmpty() }
    }
    val revealLabel = when {
        OsUtils.isMac() -> "Reveal in Finder"
        OsUtils.isWindows() -> "Show in Explorer"
        else -> "Show in Files"
    }

    Column(
        modifier = Modifier.fillMaxSize().widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Carbon.spacing.spacing09, vertical = Carbon.spacing.spacing08),
        verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing07),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04)) {
            CarbonStatusGlyph(CarbonStatus.SUCCESS, c.supportSuccess, c.background, modifier = Modifier.size(32.dp))
            Column {
                Text("G-code generated", style = Carbon.type.heading04, color = c.textPrimary)
                Text("Your scaffold tool-path is ready.", style = Carbon.type.body01, color = c.textSecondary)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
            Text("Output file", style = Carbon.type.label01, color = c.textSecondary)
            Box(
                Modifier.fillMaxWidth().background(c.layer01).border(1.dp, c.borderSubtle01, RectangleShape)
                    .padding(Carbon.spacing.spacing05),
            ) {
                Text(outputPath ?: "unknown", style = Carbon.type.code01, color = c.textPrimary)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
            CarbonButton(
                revealLabel,
                { outputPath?.let { OsUtils.revealInFileBrowser(Path(it)) } },
                enabled = outputPath != null,
                icon = CarbonIcons.Folder,
            )
            CarbonButton(
                "Start over",
                { onIntent(WizardIntent.StartOver) },
                variant = CarbonButtonVariant.TERTIARY,
                icon = CarbonIcons.Restart,
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle01))

        Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing02)) {
            Text("Summary", style = Carbon.type.label01, color = c.textSecondary)
            Text("$annotatedSlices slice(s) annotated", style = Carbon.type.body01, color = c.textPrimary)
            state.outputFolderPath?.let {
                Text("Output folder: $it", style = Carbon.type.helperText01, color = c.textHelper)
            }
        }

        // How long it took — per stage, then a Total. Saved to the Reports tab for every run.
        state.lastRunReport?.let { report ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle01))
            Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04)) {
                Text("Run timing", style = Carbon.type.label01, color = c.textSecondary)
                Box(
                    Modifier.fillMaxWidth().background(c.layer01).border(1.dp, c.borderSubtle01, RectangleShape)
                        .padding(Carbon.spacing.spacing05),
                ) {
                    RunTimingBreakdown(report)
                }
                Text(
                    "Saved to Reports — ${report.quality}, ${report.slices} slices, ${report.resolutionLabel} cube.",
                    style = Carbon.type.helperText01, color = c.textHelper,
                )
            }
        }
    }
}
