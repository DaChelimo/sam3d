package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.OsUtils
import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.state.PipelineError
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.components.CarbonButton
import edu.upenn.sam3d.ui.components.CarbonButtonSize
import edu.upenn.sam3d.ui.components.CarbonButtonVariant
import edu.upenn.sam3d.ui.components.CarbonIcons
import edu.upenn.sam3d.ui.components.CarbonInlineNotification
import edu.upenn.sam3d.ui.components.CarbonProgressBar
import edu.upenn.sam3d.ui.components.CarbonProgressIndicator
import edu.upenn.sam3d.ui.components.CarbonProgressStatus
import edu.upenn.sam3d.ui.components.CarbonStatus
import edu.upenn.sam3d.ui.components.CarbonStep
import edu.upenn.sam3d.ui.components.CarbonStepStatus
import edu.upenn.sam3d.ui.theme.Carbon
import kotlinx.coroutines.delay
import kotlin.io.path.Path
import kotlin.math.roundToInt

private val WORK_STAGES = listOf(
    PipelineStage.LOADING_DICOM,
    PipelineStage.PREPARING_SLICES,
    PipelineStage.RUNNING_INFERENCE,
    PipelineStage.BUILDING_POINT_CLOUD,
    PipelineStage.GENERATING_GCODE,
)

// Rough share of total wall-clock each stage takes (sums to 100). Inference + G-code dominate, so the
// overall bar tracks real time rather than treating all five stages as equal 20% chunks.
private val STAGE_WEIGHT = mapOf(
    PipelineStage.LOADING_DICOM to 4f,
    PipelineStage.PREPARING_SLICES to 8f,
    PipelineStage.RUNNING_INFERENCE to 45f,
    PipelineStage.BUILDING_POINT_CLOUD to 8f,
    PipelineStage.GENERATING_GCODE to 35f,
)

// Typical per-stage duration (s) — only used to gently advance the bar within a stage that reports no
// tqdm percentage, so it never looks frozen. tqdm % overrides this whenever available.
private val STAGE_EXPECTED_S = mapOf(
    PipelineStage.LOADING_DICOM to 20f,
    PipelineStage.PREPARING_SLICES to 90f,
    PipelineStage.RUNNING_INFERENCE to 600f,
    PipelineStage.BUILDING_POINT_CLOUD to 90f,
    PipelineStage.GENERATING_GCODE to 300f,
)

/**
 * Steps 3–5 (§5.4–5.6). A single **determinate** overall progress bar (stage-weighted, so it reflects
 * real time and never overflows its track), an **estimated time remaining** extrapolated from progress
 * so far, and the five pipeline stages listed with upcoming ones greyed. The elapsed ticker is gated
 * on a running state. A non-zero exit surfaces a Carbon error modal with the log tail and "Open log".
 */
@Composable
fun ProcessingScreen(state: WizardState, onIntent: (WizardIntent) -> Unit) {
    val c = Carbon.theme
    val progress = state.pipelineProgress
    val stage = progress?.stage ?: PipelineStage.LOADING_DICOM
    val curOrd = stage.ordinal.coerceAtMost(PipelineStage.GENERATING_GCODE.ordinal)
    val pct = progress?.stagePercentage ?: 0f
    val hasError = state.error != null
    val isRunning = !hasError && stage != PipelineStage.COMPLETE && stage != PipelineStage.ERROR

    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (true) { delay(1000); elapsed += 1 }
    }
    // When does the current stage begin? Used to creep the bar within no-percentage stages.
    var stageStartElapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(stage) { stageStartElapsed = elapsed }

    val overall = overallFraction(stage, curOrd, pct, elapsedInStage = (elapsed - stageStartElapsed).coerceAtLeast(0))
    val etaText = estimateRemaining(elapsed, overall, progress?.etaSeconds, isRunning)

    Column(
        modifier = Modifier.fillMaxSize().padding(Carbon.spacing.spacing09),
        verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing07),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
            Text("Processing", style = Carbon.type.heading04, color = c.textPrimary)
            Text(
                "SAM3D is running on this machine. You can step away — the estimate below updates as it goes.",
                style = Carbon.type.body01, color = c.textSecondary,
            )
        }

        // Overall progress panel
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)
                .background(c.layer01).border(1.dp, c.borderSubtle01, RectangleShape)
                .padding(Carbon.spacing.spacing06),
            verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing05),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Step ${curOrd + 1} of 5 · ${stage.label}", style = Carbon.type.headingCompact02, color = c.textPrimary)
                Text("${(overall * 100).roundToInt()}%", style = Carbon.type.headingCompact02, color = c.textPrimary)
            }
            CarbonProgressBar(
                progress = overall,
                status = if (hasError) CarbonProgressStatus.ERROR else CarbonProgressStatus.ACTIVE,
            )
            progress?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(detail, style = Carbon.type.helperText01, color = c.textHelper)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
                    Text("Elapsed", style = Carbon.type.label01, color = c.textHelper)
                    Text("%02d:%02d".format(elapsed / 60, elapsed % 60), style = Carbon.type.code01, color = c.textSecondary)
                }
                if (etaText != null) {
                    Text(etaText, style = Carbon.type.label01, color = c.textSecondary)
                }
            }
        }

        // §1: reassure the user it's safe to walk away — we hold off system sleep until it's done.
        if (isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
                Canvas(Modifier.size(8.dp)) { drawCircle(c.supportSuccess) }
                Text(
                    "Your computer is kept awake until this finishes. For multi-hour runs, stay on power (closing the lid on battery can still sleep it).",
                    style = Carbon.type.label01, color = c.textHelper,
                )
            }
        }

        // Pipeline stages — upcoming ones are greyed so the whole plan is visible up front.
        Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04)) {
            Text("PIPELINE STAGES", style = Carbon.type.label01, color = c.textHelper)
            CarbonProgressIndicator(
                steps = WORK_STAGES.map { CarbonStep(it.label) },
                statuses = WORK_STAGES.map { s ->
                    when {
                        hasError && s.ordinal == curOrd -> CarbonStepStatus.ERROR
                        s.ordinal < curOrd -> CarbonStepStatus.COMPLETE
                        s.ordinal == curOrd -> CarbonStepStatus.CURRENT
                        else -> CarbonStepStatus.INCOMPLETE
                    }
                },
                connectorHeight = 20.dp,
            )
        }
    }

    val error = state.error
    if (error is PipelineError.Server) ErrorModal(error = error, onIntent = onIntent)
}

/** Stage-weighted overall completion in 0..1, never reaching 1 until COMPLETE. */
private fun overallFraction(stage: PipelineStage, curOrd: Int, pct: Float, elapsedInStage: Long): Float {
    if (stage == PipelineStage.COMPLETE) return 1f
    val weightBefore = WORK_STAGES.filter { it.ordinal < curOrd }.sumOf { (STAGE_WEIGHT[it] ?: 0f).toDouble() }.toFloat()
    val w = STAGE_WEIGHT[WORK_STAGES.getOrElse(curOrd) { WORK_STAGES.last() }] ?: 0f
    val within = when {
        pct > 0f -> pct
        else -> (elapsedInStage / (STAGE_EXPECTED_S[stage] ?: 120f)).coerceIn(0f, 0.92f) // gentle creep
    }
    return ((weightBefore + within * w) / 100f).coerceIn(0f, 0.999f)
}

/** "~N min remaining (estimated)" from progress-so-far; falls back to tqdm's stage ETA early on. */
private fun estimateRemaining(elapsed: Long, overall: Float, stageEta: Long?, isRunning: Boolean): String? {
    if (!isRunning) return null
    // Too early to extrapolate reliably — prefer tqdm's own ETA if present, else say so.
    val remaining: Long? = when {
        elapsed >= 3 && overall in 0.03f..0.999f -> (elapsed * (1f - overall) / overall).toLong()
        stageEta != null -> stageEta
        else -> null
    }
    return when {
        remaining == null -> "Estimating time remaining…"
        remaining < 60 -> "~${remaining}s remaining (est.)"
        else -> "~${(remaining / 60.0).roundToInt()} min remaining (est.)"
    }
}

@Composable
private fun ErrorModal(error: PipelineError.Server, onIntent: (WizardIntent) -> Unit) {
    val c = Carbon.theme
    AlertDialog(
        onDismissRequest = { onIntent(WizardIntent.StartOver) },
        shape = RectangleShape,
        containerColor = c.layer01,
        title = null,
        text = {
            Box(Modifier.widthIn(max = 560.dp)) {
                CarbonInlineNotification(
                    title = "Pipeline failed (exit ${error.code})",
                    subtitle = error.hint,
                    status = CarbonStatus.ERROR,
                ) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(max = 280.dp)
                            .background(c.layer02).border(1.dp, c.borderSubtle01, RectangleShape)
                            .padding(Carbon.spacing.spacing04)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            error.body.ifBlank { "No output was captured." },
                            style = Carbon.type.code01, color = c.textSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            CarbonButton(
                "Start over", { onIntent(WizardIntent.StartOver) },
                variant = CarbonButtonVariant.PRIMARY, size = CarbonButtonSize.MD, icon = CarbonIcons.Restart,
            )
        },
        dismissButton = {
            if (error.logPath != null) {
                CarbonButton(
                    "Open log", { OsUtils.openFile(Path(error.logPath)) },
                    variant = CarbonButtonVariant.TERTIARY, size = CarbonButtonSize.MD, icon = CarbonIcons.Document,
                )
            }
        },
    )
}
