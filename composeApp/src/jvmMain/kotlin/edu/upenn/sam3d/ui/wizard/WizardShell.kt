package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.domain.model.AppView
import edu.upenn.sam3d.domain.model.WizardStep
import edu.upenn.sam3d.state.PythonStatus
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.state.WizardViewModel
import edu.upenn.sam3d.ui.components.CarbonButton
import edu.upenn.sam3d.ui.components.CarbonButtonSize
import edu.upenn.sam3d.ui.components.CarbonButtonVariant
import edu.upenn.sam3d.ui.components.CarbonIcons
import edu.upenn.sam3d.ui.components.CarbonProgressIndicator
import edu.upenn.sam3d.ui.components.CarbonStatus
import edu.upenn.sam3d.ui.components.CarbonStep
import edu.upenn.sam3d.ui.components.CarbonStepStatus
import edu.upenn.sam3d.ui.components.CarbonTag
import edu.upenn.sam3d.ui.theme.Carbon

/**
 * The app's Carbon UI shell: a fixed header (product mark + Python status), a left progress-indicator
 * rail reflecting the four wizard steps, the active screen, and a footer action bar carrying the one
 * primary action for the step. Back-navigation is offered only where §15 allows it (Prompting → Setup).
 */
@Composable
fun WizardShell(viewModel: WizardViewModel) {
    val state by viewModel.state.collectAsState()
    WizardShellContent(state = state, onIntent = viewModel::handle)
}

/** Stateless shell — takes a [WizardState] directly so it can be rendered from any state (app,
 *  previews, headless screenshot generation) without a live ViewModel. */
@Composable
fun WizardShellContent(state: WizardState, onIntent: (WizardIntent) -> Unit) {
    Column(Modifier.fillMaxSize().background(Carbon.theme.background)) {
        Header(appView = state.appView, pythonStatus = state.pythonStatus, onIntent = onIntent)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Carbon.theme.borderSubtle01))

        // Reports is a sibling of the whole wizard: full-width, no workflow rail or footer.
        if (state.appView == AppView.REPORTS) {
            Box(Modifier.weight(1f).fillMaxWidth()) { ReportsScreen(state = state) }
            return@Column
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // The workflow rail is the high-level map of the 4 phases. It's hidden on Setup (you're
            // only at the start — nothing to track yet) and appears once the real work begins.
            if (state.currentStep != WizardStep.START) {
                Rail(state = state, onIntent = onIntent)
                Box(Modifier.fillMaxHeight().width(1.dp).background(Carbon.theme.borderSubtle01))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (state.currentStep) {
                    WizardStep.START -> StartScreen(state = state, onIntent = onIntent)
                    WizardStep.PROMPTING -> PromptingScreen(state = state, onIntent = onIntent)
                    WizardStep.PROCESSING -> ProcessingScreen(state = state, onIntent = onIntent)
                    WizardStep.DONE -> DoneScreen(state = state, onIntent = onIntent)
                }
            }
        }

        if (state.currentStep != WizardStep.DONE) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Carbon.theme.borderSubtle01))
            Footer(state = state, isNextEnabled = nextEnabled(state), onIntent = onIntent)
        }
    }
}

// ── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun Header(appView: AppView, pythonStatus: PythonStatus, onIntent: (WizardIntent) -> Unit) {
    val c = Carbon.theme
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).background(c.background)
            .padding(start = Carbon.spacing.spacing05, end = Carbon.spacing.spacing05),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark()
        Spacer(Modifier.width(Carbon.spacing.spacing04))
        Text("SAM3D", style = Carbon.type.headingCompact02, color = c.textPrimary)
        Spacer(Modifier.width(Carbon.spacing.spacing03))
        Text("DICOM → G-code", style = Carbon.type.label01, color = c.textHelper)
        Spacer(Modifier.width(Carbon.spacing.spacing07))
        // Global nav: switch between the run wizard and the run-history Reports tab. Always reachable
        // (including on Setup, where the workflow rail is hidden), so reports are never stranded.
        HeaderTab("Run", selected = appView == AppView.RUN) { onIntent(WizardIntent.SetAppView(AppView.RUN)) }
        HeaderTab("Reports", selected = appView == AppView.REPORTS) { onIntent(WizardIntent.SetAppView(AppView.REPORTS)) }
        Spacer(Modifier.weight(1f))
        PythonStatusTag(pythonStatus)
    }
}

/** A header nav item: brighter when active, with a 2px Carbon-blue underline indicator. */
@Composable
private fun HeaderTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Carbon.theme
    Box(
        modifier = Modifier.fillMaxHeight().clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Carbon.spacing.spacing05),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Carbon.type.headingCompact01,
            color = if (selected) c.textPrimary else c.textSecondary,
        )
        if (selected) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(c.interactive))
        }
    }
}

/** A small wordless product mark: a wireframe cube on the interactive accent — evokes the 3D volume. */
@Composable
private fun AppMark() {
    val c = Carbon.theme
    Box(
        Modifier.size(28.dp).background(c.interactive),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width; val h = size.height; val d = w * 0.28f
            val s = Stroke(width = 1.4f, cap = StrokeCap.Round)
            val col = androidx.compose.ui.graphics.Color.White
            // front face
            drawRect(color = col, topLeft = Offset(0f, d), size = androidx.compose.ui.geometry.Size(w - d, h - d), style = s)
            // top edges
            drawLine(col, Offset(0f, d), Offset(d, 0f), 1.4f, StrokeCap.Round)
            drawLine(col, Offset(w - d, d), Offset(w, 0f), 1.4f, StrokeCap.Round)
            drawLine(col, Offset(d, 0f), Offset(w, 0f), 1.4f, StrokeCap.Round)
            // right edges
            drawLine(col, Offset(w, 0f), Offset(w, h - d), 1.4f, StrokeCap.Round)
            drawLine(col, Offset(w - d, h - d), Offset(w, h - d), 1.4f, StrokeCap.Round)
        }
    }
}

@Composable
private fun PythonStatusTag(status: PythonStatus) {
    val (label, st) = when (status) {
        PythonStatus.VERIFIED -> "Python ready" to CarbonStatus.SUCCESS
        PythonStatus.ERROR -> "Python error" to CarbonStatus.ERROR
        PythonStatus.CHECKING -> "Checking Python…" to CarbonStatus.INFO
        PythonStatus.UNCHECKED -> "Python not verified" to null
    }
    CarbonTag(text = label, status = st, showDot = true)
}

// ── Rail ────────────────────────────────────────────────────────────────────

@Composable
private fun Rail(state: WizardState, onIntent: (WizardIntent) -> Unit) {
    val c = Carbon.theme
    val liveStage = state.pipelineProgress?.stage?.label
    val steps = listOf(
        CarbonStep("Setup", "Paths & environment"),
        CarbonStep("Annotate", "Draw prompts on slices"),
        CarbonStep("Process", if (state.currentStep == WizardStep.PROCESSING) (liveStage ?: "Running pipeline") else "Inference → G-code"),
        CarbonStep("Done", "G-code ready"),
    )
    val statuses = stepStatuses(state)

    Column(
        Modifier.width(Carbon.size.railWidth).fillMaxHeight().background(c.background)
            .padding(horizontal = Carbon.spacing.spacing05, vertical = Carbon.spacing.spacing07),
    ) {
        Text("WORKFLOW", style = Carbon.type.label01, color = c.textHelper)
        Spacer(Modifier.height(Carbon.spacing.spacing06))
        CarbonProgressIndicator(
            steps = steps,
            statuses = statuses,
            // Only Prompting → Setup is a legal rail jump (§15); everything else is driven forward.
            onStepClick = if (state.currentStep == WizardStep.PROMPTING) {
                { i -> if (i == 0) onIntent(WizardIntent.GoBack) }
            } else null,
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle01))
        Spacer(Modifier.height(Carbon.spacing.spacing04))
        Text("On-device", style = Carbon.type.label01, color = c.textSecondary)
        Text("DICOM & annotations never leave this machine.", style = Carbon.type.label01, color = c.textHelper)
    }
}

private fun stepStatuses(state: WizardState): List<CarbonStepStatus> = when (state.currentStep) {
    WizardStep.START -> listOf(CarbonStepStatus.CURRENT, CarbonStepStatus.INCOMPLETE, CarbonStepStatus.INCOMPLETE, CarbonStepStatus.INCOMPLETE)
    WizardStep.PROMPTING -> listOf(CarbonStepStatus.COMPLETE, CarbonStepStatus.CURRENT, CarbonStepStatus.INCOMPLETE, CarbonStepStatus.INCOMPLETE)
    WizardStep.PROCESSING -> listOf(
        CarbonStepStatus.COMPLETE, CarbonStepStatus.COMPLETE,
        if (state.error != null) CarbonStepStatus.ERROR else CarbonStepStatus.CURRENT,
        CarbonStepStatus.INCOMPLETE,
    )
    WizardStep.DONE -> List(4) { CarbonStepStatus.COMPLETE }
}

// ── Footer ──────────────────────────────────────────────────────────────────

@Composable
private fun Footer(state: WizardState, isNextEnabled: Boolean, onIntent: (WizardIntent) -> Unit) {
    val c = Carbon.theme
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).background(c.background)
            .padding(horizontal = Carbon.spacing.spacing05),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left: Back (Prompting only) or a quiet hint for why the primary action is disabled.
        Box(contentAlignment = Alignment.CenterStart) {
            if (state.currentStep == WizardStep.PROMPTING) {
                CarbonButton("Back", { onIntent(WizardIntent.GoBack) }, variant = CarbonButtonVariant.GHOST, icon = CarbonIcons.ArrowLeft)
            } else {
                val hint = blockerHint(state, isNextEnabled)
                if (hint != null) Text(hint, style = Carbon.type.helperText01, color = c.textHelper)
            }
        }
        // Right: the single primary action for this step.
        when (state.currentStep) {
            WizardStep.START -> CarbonButton(
                "Continue", { onIntent(WizardIntent.ProceedToPrompting) },
                enabled = isNextEnabled, icon = CarbonIcons.ArrowRight,
            )
            WizardStep.PROMPTING -> CarbonButton(
                "Run pipeline", { onIntent(WizardIntent.RunPipeline) },
                enabled = isNextEnabled, icon = CarbonIcons.ArrowRight,
            )
            WizardStep.PROCESSING -> CarbonButton(
                "Cancel run", { onIntent(WizardIntent.CancelPipeline) },
                variant = CarbonButtonVariant.DANGER, size = CarbonButtonSize.LG, icon = CarbonIcons.Close,
            )
            WizardStep.DONE -> Unit
        }
    }
}

private fun blockerHint(state: WizardState, enabled: Boolean): String? {
    if (enabled || state.currentStep != WizardStep.START) return null
    return when {
        state.sam3dGcodeDir.isNullOrBlank() -> "Couldn't find the bundled pipeline/ engine — run the app from the project root"
        state.dicomFolderPath.isNullOrBlank() -> "Choose a DICOM folder to continue"
        state.outputFolderPath.isNullOrBlank() -> "Choose an output folder to continue"
        state.pythonStatus != PythonStatus.VERIFIED -> "Verify the Python environment to continue"
        state.checkpointDownload.isActive -> "Downloading the SAM checkpoint…"
        !state.checkpointExists -> "Download the SAM checkpoint to continue"
        else -> null
    }
}

private fun nextEnabled(state: WizardState): Boolean = when (state.currentStep) {
    WizardStep.START ->
        !state.sam3dGcodeDir.isNullOrBlank() &&
        !state.dicomFolderPath.isNullOrBlank() &&
        !state.outputFolderPath.isNullOrBlank() &&
        state.pythonStatus == PythonStatus.VERIFIED &&
        state.checkpointExists
    // §5.3: enabled once at least one positive polyline exists on at least one slice.
    WizardStep.PROMPTING -> state.annotations.any { ann ->
        ann.positivePolylines.any { it.isNotEmpty() }
    }
    else -> false
}
