package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.OsUtils
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import kotlin.io.path.Path

private val SuccessGreen = Color(0xFF4CAF50)

/** §5.7 — output path, OS-specific reveal button, Start Over, processing summary. */
@Composable
fun DoneScreen(state: WizardState, onIntent: (WizardIntent) -> Unit) {
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
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "✓  G-code generated successfully!",
            style = MaterialTheme.typography.headlineMedium,
            color = SuccessGreen,
            fontWeight = FontWeight.SemiBold,
        )

        Text("Output file:", style = MaterialTheme.typography.labelLarge)
        Text(
            outputPath ?: "unknown",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { outputPath?.let { OsUtils.revealInFileBrowser(Path(it)) } },
                enabled = outputPath != null,
            ) { Text(revealLabel) }
            OutlinedButton(onClick = { onIntent(WizardIntent.StartOver) }) { Text("Start Over") }
        }

        Text("Processing summary:", style = MaterialTheme.typography.labelLarge)
        Text("• Slices annotated: $annotatedSlices", style = MaterialTheme.typography.bodyMedium)
    }
}
