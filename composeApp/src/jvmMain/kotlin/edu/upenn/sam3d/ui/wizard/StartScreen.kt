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
import edu.upenn.sam3d.domain.model.QualityPreset
import edu.upenn.sam3d.process.CheckpointDownloader
import edu.upenn.sam3d.state.CheckpointDownload
import edu.upenn.sam3d.state.DicomDownsampleStatus
import edu.upenn.sam3d.state.PythonStatus
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.components.CarbonButton
import edu.upenn.sam3d.ui.components.CarbonButtonSize
import edu.upenn.sam3d.ui.components.CarbonButtonVariant
import edu.upenn.sam3d.ui.components.CarbonIcons
import edu.upenn.sam3d.ui.components.CarbonStatus
import edu.upenn.sam3d.ui.components.CarbonTag
import edu.upenn.sam3d.ui.components.CarbonTextInput
import edu.upenn.sam3d.ui.components.CheckpointBanner
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
        onIntent(WizardIntent.SetQuality(AppConfig.quality)) // restore last-chosen quality
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
        onIntent(WizardIntent.SetQuality(p))
        scope.launch(Dispatchers.IO) {
            runCatching { ConfigLoader.save(ConfigLoader.load().copy(quality = p.name, slices = p.slices)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
      Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
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

            PathField("SAM3D-GCODE directory", state.sam3dGcodeDir ?: "", "e.g. /Users/you/code/SAM3D-GCODE",
                "The folder where you cloned the SAM3D-GCODE Python engine — it must contain sam3d.py. " +
                    "This is the research pipeline that does the actual segmentation and G-code generation. " +
                    "If you don't have it yet, clone it from the SAM3D-GCODE repo first (see the project README).",
                { onIntent(WizardIntent.SetSam3dGcodeDir(it)) }) {
                scope.launch { showFilePicker("Select SAM3D-GCODE Directory", FilePickerMode.FOLDER, state.sam3dGcodeDir?.let(::File))?.let { onIntent(WizardIntent.SetSam3dGcodeDir(it)) } }
            }
            PathField("DICOM folder", state.dicomFolderPath ?: "", "e.g. /Users/you/scans/patient-001",
                "The folder holding your CT/MRI scan as a series of .dcm slice files (one file per slice). " +
                    "Pick the folder itself, not an individual .dcm file.",
                { onIntent(WizardIntent.SetDicomFolder(it)) }) {
                scope.launch { showFilePicker("Select DICOM Folder", FilePickerMode.FOLDER, state.dicomFolderPath?.let(::File))?.let { onIntent(WizardIntent.SetDicomFolder(it)) } }
            }
            PathField("Output folder", state.outputFolderPath ?: "", "e.g. /Users/you/sam3d-output",
                "An empty folder you create for the results — the 3D-printable G-code (output.gcode) and " +
                    "intermediate files are written here. Make a fresh, empty folder (e.g. \"sam3d-output\") so " +
                    "results don't get mixed up with other files.",
                { onIntent(WizardIntent.SetOutputFolder(it)) }) {
                scope.launch { showFilePicker("Select Output Folder", FilePickerMode.FOLDER, state.outputFolderPath?.let(::File))?.let { onIntent(WizardIntent.SetOutputFolder(it)) } }
            }

            PythonField(
                state = state,
                onPathChange = { onIntent(WizardIntent.SetPythonPath(it)) },
                onReverify = { onIntent(WizardIntent.VerifyPython) },
            )

            QualitySection(selected = state.quality, status = state.dicomDownsampleStatus, onSelect = ::selectQuality)
        }
      }

      // Pinned to the bottom of the Setup screen (the workflow rail is hidden on START, so this spans
      // the full shell width and reads as a window-level bar). Shows only while the checkpoint is
      // missing; becomes a live progress bar during download and vanishes once the file is present.
      CheckpointBanner(
          state = state,
          onDownload = { state.sam3dGcodeDir?.let { downloader.start(it) } },
          onCancel = { downloader.cancel(); onIntent(WizardIntent.CancelCheckpointDownload) },
          onRetry = { state.sam3dGcodeDir?.let { downloader.start(it) } },
      )
    }
}

@Composable
private fun PathField(label: String, value: String, placeholder: String, helper: String, onValueChange: (String) -> Unit, onBrowse: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
        Text(label, style = Carbon.type.label01, color = Carbon.theme.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            CarbonTextInput(value = value, onValueChange = onValueChange, placeholder = placeholder, modifier = Modifier.weight(1f))
            CarbonButton("Browse", onBrowse, variant = CarbonButtonVariant.TERTIARY, icon = CarbonIcons.Folder)
        }
        // Always-on, plain-language explanation of what this folder is and how to get it — kept below
        // the input so it stays visible after a path is chosen (the placeholder disappears once typed).
        Text(helper, style = Carbon.type.helperText01, color = Carbon.theme.textHelper)
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
            placeholder = "/opt/anaconda3/envs/SAM3D_GCODE/bin/python",
            modifier = Modifier.fillMaxWidth(),
        )
        // Always-on explanation of WHAT to enter and HOW to find it — distinct from the status line
        // below (which only reports whether the chosen binary runs).
        Text(
            "The Python interpreter that has the SAM3D engine's dependencies installed (not the system " +
                "\"python3\"). If you set the engine up with conda, run \"conda activate SAM3D_GCODE\" then " +
                "\"which python\" on macOS/Linux (or \"where python\" on Windows) and paste that path here.",
            style = Carbon.type.helperText01,
            color = c.textHelper,
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
private fun QualitySection(selected: QualityPreset, status: DicomDownsampleStatus, onSelect: (QualityPreset) -> Unit) {
    val c = Carbon.theme
    Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04)) {
        Text("Pipeline quality", style = Carbon.type.label01, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing04), modifier = Modifier.fillMaxWidth()) {
            QualityPreset.entries.forEach { preset ->
                QualityCard(preset = preset, selected = preset == selected, modifier = Modifier.weight(1f), onClick = { onSelect(preset) })
            }
        }
        Text(
            "Draft shrinks the scan to a medium cube (≈${QualityPreset.DRAFT.downsampleTargetMaxDim} voxels) and runs " +
                "${QualityPreset.DRAFT.slices} slices — a quick preview, for testing the workflow. Production keeps the " +
                "full-resolution scan and runs ${QualityPreset.PRODUCTION.slices} slices for the final scaffold. " +
                "Your choice is remembered for next time.",
            style = Carbon.type.helperText01, color = c.textHelper,
        )
        // Draft prepares a downsampled copy of the scan in the background — surface that work so a
        // brief wait before Prompting reads as progress, not a hang.
        when (status) {
            DicomDownsampleStatus.Generating ->
                Text("Preparing draft volume… (downsampling the scan)", style = Carbon.type.helperText01, color = c.textHelper)
            is DicomDownsampleStatus.Failed ->
                Text("Couldn't prepare the draft volume — running at full resolution instead. ${status.message}",
                    style = Carbon.type.helperText01, color = c.textError)
            else -> Unit
        }
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
            Text(preset.label, style = Carbon.type.headingCompact02, color = c.textPrimary)
            RadioDot(selected)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
            CarbonTag(if (preset.downsamples) "≈${preset.downsampleTargetMaxDim}³ · ${preset.slices} slices" else "full · ${preset.slices} slices",
                status = if (selected) CarbonStatus.INFO else null)
            Text(preset.eta, style = Carbon.type.headingCompact01, color = c.textPrimary)
        }
        Text(preset.description, style = Carbon.type.helperText01, color = c.textHelper)
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
