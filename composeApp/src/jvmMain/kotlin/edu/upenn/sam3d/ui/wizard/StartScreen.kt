package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.AppConfig
import edu.upenn.sam3d.ConfigLoader
import edu.upenn.sam3d.process.CheckpointDownloader
import edu.upenn.sam3d.state.CheckpointDownload
import edu.upenn.sam3d.state.PythonStatus
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.components.CarbonButton
import edu.upenn.sam3d.ui.components.CarbonButtonSize
import edu.upenn.sam3d.ui.components.CarbonButtonVariant
import edu.upenn.sam3d.ui.components.CarbonIcons
import edu.upenn.sam3d.ui.components.CarbonStatus
import edu.upenn.sam3d.ui.components.CarbonStatusGlyph
import edu.upenn.sam3d.ui.components.CarbonTag
import edu.upenn.sam3d.ui.components.CarbonTextInput
import edu.upenn.sam3d.ui.components.CheckpointDownloadBar
import edu.upenn.sam3d.ui.components.FilePickerMode
import edu.upenn.sam3d.ui.components.showFilePicker
import edu.upenn.sam3d.ui.theme.Carbon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

private data class QualityPreset(val name: String, val slices: Int, val eta: String, val desc: String)

// The only two settings devs actually pick between — slice count is hidden behind a plain-language
// quality choice with a time estimate. (Edit these numbers/times here if the engine timing changes.)
private val QUALITY_PRESETS = listOf(
    QualityPreset("Draft", 8, "≈ 15–20 min", "Fast, lower detail — for testing the workflow."),
    QualityPreset("Production", 120, "≈ 3–4 hr", "Full detail — for the final scaffold."),
)

private fun presetIndexFor(slices: Int): Int =
    QUALITY_PRESETS.indexOfFirst { it.slices == slices }.takeIf { it >= 0 }
        ?: if (slices <= 20) 0 else 1   // nearest bucket for any custom value

/**
 * Step 1 — Setup (§5.2). Carbon form: three native folder pickers, a Python binary that **verifies
 * itself** (no manual click), a plain-language **quality** toggle (Draft vs Production, with time
 * estimates) standing in for the raw `-s` slice count, and the SAM checkpoint status with a live
 * download. Fields prefill from per-user config.
 */
@Composable
fun StartScreen(state: WizardState, onIntent: (WizardIntent) -> Unit) {
    val scope = rememberCoroutineScope()
    val downloader = remember { CheckpointDownloader() }

    LaunchedEffect(Unit) {
        if (state.sam3dGcodeDir == null) AppConfig.sam3dGcodeDir?.let { onIntent(WizardIntent.SetSam3dGcodeDir(it)) }
        if (state.dicomFolderPath == null) AppConfig.dicomFolderPath?.let { onIntent(WizardIntent.SetDicomFolder(it)) }
        if (state.outputFolderPath == null) AppConfig.outputFolderPath?.let { onIntent(WizardIntent.SetOutputFolder(it)) }
        if (state.pythonPath == "python3") onIntent(WizardIntent.SetPythonPath(AppConfig.pythonPath))
        onIntent(WizardIntent.SetSlices(AppConfig.slices)) // restore last-chosen quality
    }

    // Auto-verify Python so the user never has to click Verify: kick off a check shortly after the
    // path is prefilled or edited (debounced). Re-runs because SetPythonPath resets status to UNCHECKED.
    LaunchedEffect(state.pythonPath) {
        if (state.pythonStatus == PythonStatus.VERIFIED || state.pythonStatus == PythonStatus.CHECKING) return@LaunchedEffect
        if (state.pythonPath.isBlank()) return@LaunchedEffect
        delay(500)
        onIntent(WizardIntent.VerifyPython)
    }

    LaunchedEffect(downloader) {
        downloader.state.collect { st ->
            onIntent(WizardIntent.SetCheckpointDownload(st))
            if (st is CheckpointDownload.Succeeded) onIntent(WizardIntent.SetCheckpointExists(true))
        }
    }

    LaunchedEffect(state.sam3dGcodeDir) {
        val dir = state.sam3dGcodeDir ?: return@LaunchedEffect
        if (state.checkpointDownload.isActive) return@LaunchedEffect
        val exists = withContext(Dispatchers.IO) { Files.exists(Paths.get(dir, "checkpoints", "sam_vit_h_4b8939.pth")) }
        onIntent(WizardIntent.SetCheckpointExists(exists))
    }

    LaunchedEffect(state.pythonStatus) {
        if (state.pythonStatus != PythonStatus.CHECKING) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder(listOf(state.pythonPath, "--version")).redirectErrorStream(true).start()
                proc.waitFor()
                if (proc.exitValue() == 0) PythonStatus.VERIFIED else PythonStatus.ERROR
            } catch (_: Exception) { PythonStatus.ERROR }
        }
        onIntent(WizardIntent.SetPythonStatus(result))
    }

    fun selectQuality(p: QualityPreset) {
        onIntent(WizardIntent.SetSlices(p.slices))
        scope.launch(Dispatchers.IO) { runCatching { ConfigLoader.save(ConfigLoader.load().copy(slices = p.slices)) } }
    }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)
                .padding(horizontal = Carbon.spacing.spacing09, vertical = Carbon.spacing.spacing08),
            verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing07),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
                Text("Set up your run", style = Carbon.type.heading04, color = Carbon.theme.textPrimary)
                Text("Point SAM3D at the engine, your DICOM series, and an output folder. Everything runs locally.",
                    style = Carbon.type.body01, color = Carbon.theme.textSecondary)
            }

            PathField("SAM3D-GCODE directory", state.sam3dGcodeDir ?: "", "Path to the folder containing sam3d.py",
                { onIntent(WizardIntent.SetSam3dGcodeDir(it)) }) {
                scope.launch { showFilePicker("Select SAM3D-GCODE Directory", FilePickerMode.FOLDER, state.sam3dGcodeDir?.let(::File))?.let { onIntent(WizardIntent.SetSam3dGcodeDir(it)) } }
            }
            PathField("DICOM folder", state.dicomFolderPath ?: "", "Folder containing the .dcm series",
                { onIntent(WizardIntent.SetDicomFolder(it)) }) {
                scope.launch { showFilePicker("Select DICOM Folder", FilePickerMode.FOLDER, state.dicomFolderPath?.let(::File))?.let { onIntent(WizardIntent.SetDicomFolder(it)) } }
            }
            PathField("Output folder", state.outputFolderPath ?: "", "Where the generated G-code is written",
                { onIntent(WizardIntent.SetOutputFolder(it)) }) {
                scope.launch { showFilePicker("Select Output Folder", FilePickerMode.FOLDER, state.outputFolderPath?.let(::File))?.let { onIntent(WizardIntent.SetOutputFolder(it)) } }
            }

            PythonField(
                state = state,
                onPathChange = { onIntent(WizardIntent.SetPythonPath(it)) },
                onReverify = { onIntent(WizardIntent.VerifyPython) },
            )

            QualitySection(selectedIndex = presetIndexFor(state.slices), onSelect = ::selectQuality)

            CheckpointSection(
                state = state,
                onDownload = { state.sam3dGcodeDir?.let { downloader.start(it) } },
                onCancel = { downloader.cancel(); onIntent(WizardIntent.CancelCheckpointDownload) },
            )
        }
    }
}

@Composable
private fun PathField(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit, onBrowse: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
        Text(label, style = Carbon.type.label01, color = Carbon.theme.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            CarbonTextInput(value = value, onValueChange = onValueChange, placeholder = placeholder, modifier = Modifier.weight(1f))
            CarbonButton("Browse", onBrowse, variant = CarbonButtonVariant.TERTIARY, icon = CarbonIcons.Folder)
        }
    }
}

@Composable
private fun PythonField(state: WizardState, onPathChange: (String) -> Unit, onReverify: () -> Unit) {
    val c = Carbon.theme
    Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
            Text("Python binary", style = Carbon.type.label01, color = c.textSecondary)
            when (state.pythonStatus) {
                PythonStatus.VERIFIED -> CarbonTag("Ready", status = CarbonStatus.SUCCESS, showDot = true)
                PythonStatus.CHECKING -> CarbonTag("Checking…", status = CarbonStatus.INFO, showDot = true)
                PythonStatus.ERROR -> CarbonTag("Not working", status = CarbonStatus.ERROR, showDot = true)
                PythonStatus.UNCHECKED -> Unit
            }
        }
        CarbonTextInput(
            value = state.pythonPath,
            onValueChange = onPathChange,
            placeholder = "/opt/anaconda3/envs/sam3d/bin/python",
            modifier = Modifier.fillMaxWidth(),
        )
        when (state.pythonStatus) {
            PythonStatus.VERIFIED -> Text("Verified automatically — interpreter responded to --version.", style = Carbon.type.helperText01, color = c.textHelper)
            PythonStatus.ERROR -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04)) {
                Text("Couldn't run that binary. Point it at the sam3d conda environment.", style = Carbon.type.helperText01, color = c.textError)
                CarbonButton("Re-check", onReverify, variant = CarbonButtonVariant.GHOST, size = CarbonButtonSize.SM)
            }
            else -> Text("Verifying automatically when you set the path — no need to click anything.", style = Carbon.type.helperText01, color = c.textHelper)
        }
    }
}

@Composable
private fun QualitySection(selectedIndex: Int, onSelect: (QualityPreset) -> Unit) {
    val c = Carbon.theme
    Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04)) {
        Text("Pipeline quality", style = Carbon.type.label01, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04), modifier = Modifier.fillMaxWidth()) {
            QUALITY_PRESETS.forEachIndexed { i, preset ->
                QualityCard(preset = preset, selected = i == selectedIndex, modifier = Modifier.weight(1f), onClick = { onSelect(preset) })
            }
        }
        Text("Controls how hard the engine works (sam3d.py “-s”). Your choice is remembered for next time.",
            style = Carbon.type.helperText01, color = c.textHelper)
    }
}

@Composable
private fun QualityCard(preset: QualityPreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Carbon.theme
    Column(
        modifier = modifier
            .background(if (selected) c.layerHover01 else c.layer01, RectangleShape)
            .border(if (selected) Carbon.size.borderStrongWidth else 1.dp, if (selected) c.borderInteractive else c.borderSubtle01, RectangleShape)
            .clickable { onClick() }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(Carbon.spacing.spacing05),
        verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(preset.name, style = Carbon.type.headingCompact02, color = c.textPrimary)
            RadioDot(selected)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
            CarbonTag("${preset.slices} slices", status = if (selected) CarbonStatus.INFO else null)
            Text(preset.eta, style = Carbon.type.headingCompact01, color = c.textPrimary)
        }
        Text(preset.desc, style = Carbon.type.helperText01, color = c.textHelper)
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val c = Carbon.theme
    Canvas(Modifier.size(18.dp)) {
        val r = size.minDimension / 2f
        drawCircle(if (selected) c.interactive else c.borderStrong01, radius = r - 1f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        if (selected) drawCircle(c.interactive, radius = r * 0.5f)
    }
}

@Composable
private fun CheckpointSection(state: WizardState, onDownload: () -> Unit, onCancel: () -> Unit) {
    val c = Carbon.theme
    Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04)) {
        Text("SAM checkpoint", style = Carbon.type.label01, color = c.textSecondary)
        when {
            state.sam3dGcodeDir.isNullOrBlank() ->
                Text("Select the SAM3D-GCODE directory first.", style = Carbon.type.body01, color = c.textHelper)

            state.checkpointExists -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
                CarbonStatusGlyph(CarbonStatus.SUCCESS, c.supportSuccess, c.background, modifier = Modifier.size(20.dp))
                Text("sam_vit_h_4b8939.pth found", style = Carbon.type.body01, color = c.textPrimary)
            }

            state.checkpointDownload.isActive || state.checkpointDownload is CheckpointDownload.Failed ->
                CheckpointDownloadBar(state = state.checkpointDownload, onCancel = onCancel, onRetry = onDownload)

            else -> Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
                    CarbonTag("Not downloaded", status = CarbonStatus.WARNING)
                    Text("Required for inference · 2.4 GB", style = Carbon.type.helperText01, color = c.textHelper)
                }
                CarbonButton("Download checkpoint", onDownload, variant = CarbonButtonVariant.TERTIARY, size = CarbonButtonSize.LG, icon = CarbonIcons.Download)
            }
        }
    }
}
