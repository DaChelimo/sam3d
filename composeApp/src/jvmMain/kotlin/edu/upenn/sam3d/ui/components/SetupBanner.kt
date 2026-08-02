package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.process.EnvironmentSetupManager
import edu.upenn.sam3d.state.EnvSetup
import edu.upenn.sam3d.state.PythonStatus
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.theme.Carbon
import kotlin.math.roundToInt

/**
 * Persistent bottom bar on the Setup screen that runs the **one-click environment setup** — build a
 * Python venv, install the pipeline's dependencies, and download the SAM checkpoint, in one flow.
 * Replaces the old checkpoint-only banner.
 *
 * It shows whenever the environment isn't ready (no verified Python + checkpoint), and does NOT
 * disappear mid-run — it becomes a live progress bar with a Cancel affordance, matching how a healthy
 * long download reads as progress rather than a hang. When setup finishes (Python verifies green and
 * the checkpoint is present) it renders nothing and the footer's Continue unlocks.
 *
 * @param onStart begin (or resume) setup.
 * @param onCancel stop an in-flight stage (partial work is kept so a later run resumes).
 * @param onRetry re-run after a failure (uv/pip resume, checkpoint resumes from its .part).
 */
@Composable
fun SetupBanner(
    state: WizardState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ready ⇒ nothing to show. The screen stays uncluttered and the footer's Continue is enabled.
    val ready = state.pythonStatus == PythonStatus.VERIFIED && state.checkpointExists
    if (ready && !state.envSetup.isActive && state.envSetup !is EnvSetup.Failed) return

    val c = Carbon.theme
    val dirSet = !state.sam3dGcodeDir.isNullOrBlank()

    Column(modifier.fillMaxWidth().background(c.layer01)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle01))
        Box(
            Modifier.fillMaxWidth().padding(
                horizontal = Carbon.spacing.spacing07,
                vertical = Carbon.spacing.spacing05,
            )
        ) {
            when (val s = state.envSetup) {
                is EnvSetup.PreparingUv ->
                    ProgressRow("Setting up environment", "Preparing the installer…", null, onCancel)

                is EnvSetup.InstallingPython ->
                    ProgressRow("Setting up environment", "Installing Python ${EnvironmentSetupManager.PYTHON_VERSION}…", null, onCancel)

                is EnvSetup.CreatingVenv ->
                    ProgressRow("Setting up environment", "Creating the virtual environment…", null, onCancel)

                is EnvSetup.InstallingDeps ->
                    ProgressRow("Installing dependencies", s.line.take(90).ifBlank { "Installing…" }, null, onCancel)

                is EnvSetup.DownloadingCheckpoint -> {
                    val frac = s.fraction
                    val helper = when {
                        frac != null -> "SAM checkpoint · ${(frac * 100).roundToInt()}%  ·  ${fmtBytes(s.receivedBytes)} / ${fmtBytes(s.totalBytes!!)}"
                        else -> "SAM checkpoint · ${fmtBytes(s.receivedBytes)} downloaded"
                    }
                    ProgressRow("Downloading model checkpoint", helper, frac, onCancel)
                }

                is EnvSetup.Verifying ->
                    ProgressRow("Finishing up", "Verifying the environment…", null, onCancel)

                is EnvSetup.Failed -> IdleOrFailedRow(dirSet = dirSet, failedMessage = s.message, onAction = onRetry)

                is EnvSetup.Succeeded ->
                    ProgressRow("Environment ready", "Verifying…", 1f, onCancel)

                EnvSetup.Idle -> IdleOrFailedRow(dirSet = dirSet, failedMessage = null, onAction = onStart)
            }
        }
    }
}

/** Connecting / in-progress: the bar becomes a progress indicator with a Cancel affordance. */
@Composable
private fun ProgressRow(label: String, helper: String, fraction: Float?, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CarbonProgressBar(
            modifier = Modifier.weight(1f),
            label = label,
            helperText = helper,
            progress = fraction,
        )
        Spacer(Modifier.width(Carbon.spacing.spacing05))
        CarbonButton(
            text = "Cancel",
            onClick = onCancel,
            variant = CarbonButtonVariant.GHOST,
            size = CarbonButtonSize.MD,
            icon = CarbonIcons.Close,
        )
    }
}

/**
 * Resting state: explains the one-click setup and offers to start it. Doubles as the failure state —
 * same layout, recoloured with the error message and a Retry verb — so a failed run shows the same
 * affordance in the same place (and Retry resumes rather than restarting from scratch).
 */
@Composable
private fun IdleOrFailedRow(dirSet: Boolean, failedMessage: String?, onAction: () -> Unit) {
    val c = Carbon.theme
    val failed = failedMessage != null

    val title = when {
        failed -> "Environment setup failed"
        !dirSet -> "Pipeline engine not found"
        else -> "Set up the pipeline environment"
    }
    val explanation = when {
        failedMessage != null -> failedMessage
        dirSet -> "One click installs Python, builds the environment, installs the pipeline's " +
            "dependencies, and downloads the SAM model checkpoint (~2.4 GB) — no setup required on your " +
            "part. This is a one-time step; if interrupted, it resumes where it left off."
        // The Setup screen shows a folder picker in this case; point at it rather than telling the
        // user to "run from the project root", which is meaningless for an installed build.
        else -> "Setup can't start until SAM3D knows where its Python engine is. Use the " +
            "\"Pipeline engine folder\" field above, or reinstall the app to restore the bundled copy."
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CarbonStatusGlyph(
            status = if (failed) CarbonStatus.ERROR else CarbonStatus.WARNING,
            color = if (failed) c.supportError else c.supportWarning,
            knockout = c.background,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Carbon.spacing.spacing04))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing02)) {
            Text(title, style = Carbon.type.headingCompact01, color = c.textPrimary)
            Text(explanation, style = Carbon.type.helperText01, color = c.textHelper)
        }
        Spacer(Modifier.width(Carbon.spacing.spacing05))
        CarbonButton(
            text = if (failed) "Retry" else "Set up environment",
            onClick = onAction,
            variant = CarbonButtonVariant.PRIMARY,
            size = CarbonButtonSize.MD,
            enabled = dirSet,
            icon = if (failed) CarbonIcons.Renew else CarbonIcons.Download,
        )
    }
}

private fun fmtBytes(b: Long): String {
    val gb = b / 1_000_000_000.0
    val mb = b / 1_000_000.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(mb)
}
