package edu.upenn.sam3d.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import edu.upenn.sam3d.BuildInfo
import edu.upenn.sam3d.OsUtils
import edu.upenn.sam3d.domain.model.UpdateStatus

/**
 * Tells the user a newer build exists and sends them to the release page.
 *
 * The subtitle carries the message that actually matters to a lab user, who has an hour of checkpoint
 * download behind them and reasonably assumes an update means doing that again: it doesn't. The model
 * checkpoint and Python environment live in the user data folder, which no install touches — so
 * updating costs one ~200 MB download and nothing else.
 *
 * Deliberately notify-only. Swapping a running app's own folder on Windows means exiting first and
 * letting a helper script do it, and a half-completed swap leaves no working install at all — a bad
 * trade for a tool someone may be mid-run on.
 */
@Composable
fun UpdateBanner(
    update: UpdateStatus.Available,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    CarbonInlineNotification(
        title = "SAM3D ${update.version} is available",
        subtitle = "You're on ${BuildInfo.VERSION}. Your model checkpoint and Python environment " +
            "are kept — updating only replaces the app itself.",
        status = CarbonStatus.INFO,
        modifier = modifier,
        onClose = onDismiss,
    ) {
        // One button, not a "Download" / "What's new" pair: the release page is both the notes and
        // the assets, and two controls going to the same URL is just noise.
        CarbonButton(
            text = "Get the update",
            onClick = { OsUtils.openUrl(update.url) },
            variant = CarbonButtonVariant.PRIMARY,
            size = CarbonButtonSize.SM,
            icon = CarbonIcons.Download,
        )
    }
}
