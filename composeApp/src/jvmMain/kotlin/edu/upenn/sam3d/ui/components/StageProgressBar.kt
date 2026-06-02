package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Determinate when [percentage] > 0 (drives off the tqdm value), indeterminate otherwise — so the
 * bar animates during stages that report no parseable progress (e.g. reslicing).
 */
@Composable
fun StageProgressBar(percentage: Float, modifier: Modifier = Modifier) {
    if (percentage > 0f) {
        LinearProgressIndicator(
            progress = { percentage.coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}
