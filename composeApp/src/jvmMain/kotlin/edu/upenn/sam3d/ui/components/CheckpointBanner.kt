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
import edu.upenn.sam3d.state.CheckpointDownload
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.theme.Carbon
import kotlin.math.roundToInt

/**
 * Persistent bottom bar (Phase 6 follow-up) surfacing the SAM checkpoint — modelled on the
 * "relaunch to update" bar pattern. Rendered pinned at the bottom of the Setup screen and **only
 * when the checkpoint is missing**: it explains what the checkpoint is, offers a one-click download,
 * and — crucially — does NOT disappear while downloading; it turns into a live progress bar. Once the
 * file is present it renders nothing (the surrounding screen is clean and Continue unlocks).
 *
 * Continue stays disabled the whole time this bar is visible because `nextEnabled` requires
 * `checkpointExists`, which only flips true on a successful download.
 */
@Composable
fun CheckpointBanner(
    state: WizardState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Present ⇒ nothing to show. The screen stays uncluttered and the footer's Continue is enabled.
    if (state.checkpointExists) return

    val c = Carbon.theme
    Column(modifier.fillMaxWidth().background(c.layer01)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle01))
        Box(
            Modifier.fillMaxWidth().padding(
                horizontal = Carbon.spacing.spacing07,
                vertical = Carbon.spacing.spacing05,
            )
        ) {
            when (val dl = state.checkpointDownload) {
                is CheckpointDownload.Connecting ->
                    ProgressRow(label = "Downloading SAM checkpoint (2.4 GB)", helper = "Connecting…", fraction = null, onCancel = onCancel)

                is CheckpointDownload.InProgress -> {
                    val frac = dl.fraction
                    val helper = when {
                        frac != null -> "${(frac * 100).roundToInt()}%  ·  ${fmtBytes(dl.receivedBytes)} / ${fmtBytes(dl.totalBytes!!)}"
                        else -> "${fmtBytes(dl.receivedBytes)} downloaded"
                    }
                    ProgressRow(label = "Downloading SAM checkpoint", helper = helper, fraction = frac, onCancel = onCancel)
                }

                is CheckpointDownload.Failed -> IdleOrFailedRow(
                    state = state,
                    failedMessage = dl.message,
                    onAction = onRetry,
                )

                else -> IdleOrFailedRow(state = state, failedMessage = null, onAction = onDownload)
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
 * Resting state: explains the checkpoint and offers the download. Doubles as the failure state — same
 * layout, just recoloured with the error message and a Retry verb — so a failed download still shows
 * the same affordance in the same place.
 */
@Composable
private fun IdleOrFailedRow(state: WizardState, failedMessage: String?, onAction: () -> Unit) {
    val c = Carbon.theme
    val dirSet = !state.sam3dGcodeDir.isNullOrBlank()
    val failed = failedMessage != null

    val title = if (failed) "Checkpoint download failed" else "SAM model checkpoint required"
    val explanation = when {
        failedMessage != null -> failedMessage
        dirSet -> "The Segment Anything model weights (~2.4 GB) the engine needs to run. This is a one-time download."
        else -> "The Segment Anything model weights (~2.4 GB) the engine needs to run. Choose the SAM3D-GCODE folder above first to enable the download."
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
            text = if (failed) "Retry download" else "Download checkpoint",
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
