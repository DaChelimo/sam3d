package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import edu.upenn.sam3d.state.CheckpointDownload
import edu.upenn.sam3d.ui.theme.Carbon
import kotlin.math.roundToInt

/**
 * Drives the SAM checkpoint download UI (Phase 6 task 4) off [CheckpointDownload]: an indeterminate
 * bar while connecting, a determinate `received / total` bar while streaming with a Cancel action, or
 * an inline error with Retry on failure. Renders nothing when idle/succeeded — the surrounding row
 * shows the "found" state instead.
 */
@Composable
fun CheckpointDownloadBar(
    state: CheckpointDownload,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is CheckpointDownload.Connecting -> Column(modifier) {
            CarbonProgressBar(
                label = "Downloading checkpoint (2.4 GB)",
                helperText = "Connecting…",
                progress = null,
            )
            Spacer(Modifier.height(Carbon.spacing.spacing04))
            CancelRow(onCancel)
        }

        is CheckpointDownload.InProgress -> Column(modifier) {
            val frac = state.fraction
            val helper = when {
                frac != null -> "${(frac * 100).roundToInt()}%  ·  ${fmtBytes(state.receivedBytes)} / ${fmtBytes(state.totalBytes!!)}"
                else -> "${fmtBytes(state.receivedBytes)} downloaded"
            }
            CarbonProgressBar(
                label = "Downloading checkpoint",
                helperText = helper,
                progress = frac,
            )
            Spacer(Modifier.height(Carbon.spacing.spacing04))
            CancelRow(onCancel)
        }

        is CheckpointDownload.Failed -> Column(modifier) {
            CarbonInlineNotification(
                title = "Checkpoint download failed",
                subtitle = state.message,
                status = CarbonStatus.ERROR,
            )
            Spacer(Modifier.height(Carbon.spacing.spacing04))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CarbonButton(
                    text = "Retry",
                    onClick = onRetry,
                    variant = CarbonButtonVariant.TERTIARY,
                    size = CarbonButtonSize.SM,
                    icon = CarbonIcons.Download,
                )
            }
        }

        else -> Unit
    }
}

@Composable
private fun CancelRow(onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        CarbonButton(
            text = "Cancel",
            onClick = onCancel,
            variant = CarbonButtonVariant.GHOST,
            size = CarbonButtonSize.SM,
            icon = CarbonIcons.Close,
        )
    }
}

private fun fmtBytes(b: Long): String {
    val gb = b / 1_000_000_000.0
    val mb = b / 1_000_000.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(mb)
}
