package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.state.PipelineError
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.components.StageProgressBar
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Steps 3-5 (§5.4-5.6) — one screen, driven by [WizardState.pipelineProgress] as sam3d.py runs.
 * The ViewModel auto-advances to Done on COMPLETE; a non-zero exit surfaces the error dialog below.
 */
@Composable
fun ProcessingScreen(state: WizardState, onIntent: (WizardIntent) -> Unit) {
    val progress = state.pipelineProgress
    val stage = progress?.stage ?: PipelineStage.LOADING_DICOM
    // The 5 work stages are LOADING_DICOM..GENERATING_GCODE; COMPLETE/ERROR are terminal.
    val isWorkStage = stage.ordinal <= PipelineStage.GENERATING_GCODE.ordinal
    val stageHeading = if (isWorkStage) "Stage ${stage.ordinal + 1}/5: ${stage.label}" else stage.label

    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed += 1
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val pct = progress?.stagePercentage ?: 0f
        Text("Processing", style = MaterialTheme.typography.headlineMedium)
        Text(stageHeading, style = MaterialTheme.typography.titleMedium)
        StageProgressBar(percentage = pct)
        if (pct > 0f) {
            Text(
                "${(pct * 100).roundToInt()}% of this stage complete",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Secondary line: what sam3d.py is doing right now, parsed from its stdout.
        progress?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            "Elapsed: %02d:%02d".format(elapsed / 60, elapsed % 60),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { onIntent(WizardIntent.CancelPipeline) }) { Text("Cancel") }
    }

    // §STEP 7: non-zero exit → dialog with the last lines of the subprocess log.
    val error = state.error
    if (error is PipelineError.Server) {
        AlertDialog(
            onDismissRequest = { onIntent(WizardIntent.StartOver) },
            title = { Text("Pipeline failed (exit ${error.code})") },
            text = {
                Text(
                    text = error.body.ifBlank { "No output was captured." },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { onIntent(WizardIntent.StartOver) }) { Text("Start Over") }
            },
        )
    }
}
