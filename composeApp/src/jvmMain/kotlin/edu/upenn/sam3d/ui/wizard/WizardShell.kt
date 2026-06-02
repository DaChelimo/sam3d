package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.domain.model.WizardStep
import edu.upenn.sam3d.state.PythonStatus
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.state.WizardViewModel

private val SuccessGreen = Color(0xFF4CAF50.toInt())

private data class RailStep(val label: String)

private val railSteps = listOf(
    RailStep("Start"),
    RailStep("Prompting"),
    RailStep("Inference"),
    RailStep("Point Cloud"),
    RailStep("G-code"),
)

private fun currentRailIndex(step: WizardStep): Int = when (step) {
    WizardStep.START -> 0
    WizardStep.PROMPTING -> 1
    WizardStep.PROCESSING -> 2
    WizardStep.DONE -> railSteps.size  // all completed
}

@Composable
fun WizardShell(viewModel: WizardViewModel) {
    val state by viewModel.state.collectAsState()
    val currentIndex = currentRailIndex(state.currentStep)
    val isNextEnabled = nextEnabled(state)

    Row(modifier = Modifier.fillMaxSize()) {
        // Left: NavigationRail
        NavigationRail(
            modifier = Modifier.width(200.dp).fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Spacer(Modifier.height(24.dp))
            railSteps.forEachIndexed { index, step ->
                val isCompleted = index < currentIndex
                val isCurrent = index == currentIndex
                val isEnabled = index <= currentIndex

                NavigationRailItem(
                    selected = isCurrent,
                    onClick = {
                        if (isEnabled && !isCurrent) {
                            // Only back-navigation is meaningful for now
                            if (index == 0) viewModel.handle(WizardIntent.GoBack)
                        }
                    },
                    enabled = isEnabled,
                    icon = {
                        Text(
                            text = when {
                                isCompleted -> "✓"
                                isCurrent -> "●"
                                else -> "${index + 1}"
                            },
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    label = { Text(step.label) },
                )
            }
        }

        // Right: top bar + screen content + bottom bar
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            TopBar(pythonStatus = state.pythonStatus)
            HorizontalDivider()

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.currentStep) {
                    WizardStep.START -> StartScreen(state = state, onIntent = viewModel::handle)
                    WizardStep.PROMPTING -> PromptingScreen(state = state, onIntent = viewModel::handle)
                    WizardStep.PROCESSING -> ProcessingScreen(state = state, onIntent = viewModel::handle)
                    WizardStep.DONE -> DoneScreen(state = state, onIntent = viewModel::handle)
                }
            }

            HorizontalDivider()
            BottomBar(state = state, isNextEnabled = isNextEnabled, onIntent = viewModel::handle)
        }
    }
}

@Composable
private fun TopBar(pythonStatus: PythonStatus) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("SAM3D", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        PythonStatusIndicator(pythonStatus)
    }
}

@Composable
private fun PythonStatusIndicator(status: PythonStatus) {
    val (label, color) = when (status) {
        PythonStatus.VERIFIED -> "● Python ready" to SuccessGreen
        PythonStatus.ERROR -> "✗ Python error" to MaterialTheme.colorScheme.error
        PythonStatus.CHECKING -> "⟳ Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        PythonStatus.UNCHECKED -> "○ Python not verified" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun BottomBar(
    state: WizardState,
    isNextEnabled: Boolean,
    onIntent: (WizardIntent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Back button (only visible when navigation back is possible)
        if (state.currentStep == WizardStep.PROMPTING) {
            OutlinedButton(onClick = { onIntent(WizardIntent.GoBack) }) {
                Text("Back")
            }
        } else {
            Spacer(Modifier.width(88.dp))
        }

        // Next / Run button
        when (state.currentStep) {
            WizardStep.START -> Button(
                onClick = { onIntent(WizardIntent.ProceedToPrompting) },
                enabled = isNextEnabled,
            ) { Text("Next") }

            WizardStep.PROMPTING -> Button(
                onClick = { onIntent(WizardIntent.RunPipeline) },
                enabled = isNextEnabled,
            ) { Text("Run Pipeline") }

            WizardStep.PROCESSING -> OutlinedButton(
                onClick = { onIntent(WizardIntent.CancelPipeline) },
            ) { Text("Cancel") }

            WizardStep.DONE -> Button(
                onClick = { onIntent(WizardIntent.StartOver) },
            ) { Text("Start Over") }
        }
    }
}

private fun nextEnabled(state: WizardState): Boolean = when (state.currentStep) {
    WizardStep.START ->
        state.sam3dGcodeDir != null &&
        !state.sam3dGcodeDir.isBlank() &&
        state.dicomFolderPath != null &&
        !state.dicomFolderPath.isBlank() &&
        state.outputFolderPath != null &&
        !state.outputFolderPath.isBlank() &&
        state.pythonStatus == PythonStatus.VERIFIED &&
        state.checkpointExists
    // §5.3: enabled once at least one positive polyline exists on at least one slice.
    WizardStep.PROMPTING -> state.annotations.any { ann ->
        ann.positivePolylines.any { it.isNotEmpty() }
    }
    else -> false
}
