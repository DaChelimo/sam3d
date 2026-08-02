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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.AppConfig
import edu.upenn.sam3d.ConfigLoader
import edu.upenn.sam3d.OsUtils
import edu.upenn.sam3d.domain.model.QualityPreset
import edu.upenn.sam3d.engine.EngineStager
import edu.upenn.sam3d.process.EnvironmentSetupManager
import edu.upenn.sam3d.state.DicomDownsampleStatus
import edu.upenn.sam3d.state.EnvSetup
import edu.upenn.sam3d.state.PythonStatus
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.components.CarbonButton
import edu.upenn.sam3d.ui.components.CarbonButtonVariant
import edu.upenn.sam3d.ui.components.CarbonIcons
import edu.upenn.sam3d.ui.components.CarbonStatus
import edu.upenn.sam3d.ui.components.CarbonTag
import edu.upenn.sam3d.ui.components.CarbonTextInput
import edu.upenn.sam3d.ui.components.FilePickerMode
import edu.upenn.sam3d.ui.components.SetupBanner
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
    // The one-click environment setup manager — rebuilt if the resolved pipeline dir changes. Null
    // until the pipeline dir is known (its Set-up button stays disabled meanwhile).
    val pipelineDir = state.sam3dGcodeDir
    val setup = remember(pipelineDir) { pipelineDir?.let { EnvironmentSetupManager(pipelineDir = it) } }

    // Resolving the engine can stage files out of the app bundle on first launch, so it's off-thread.
    LaunchedEffect(Unit) {
        if (state.sam3dGcodeDir == null) {
            withContext(Dispatchers.IO) { AppConfig.sam3dGcodeDir }
                ?.let { onIntent(WizardIntent.SetSam3dGcodeDir(it)) }
        }
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

    // Stream the environment-setup manager's progress into state. On success, point the app at the
    // freshly built venv (auto-verifies to VERIFIED) and persist it so next launch is ready instantly.
    LaunchedEffect(setup) {
        setup?.state?.collect { st ->
            onIntent(WizardIntent.SetEnvSetup(st))
            if (st is EnvSetup.Succeeded) {
                val venvPy = setup.venvPythonPath()
                onIntent(WizardIntent.SetPythonPath(venvPy))
                scope.launch(Dispatchers.IO) {
                    runCatching { ConfigLoader.save(ConfigLoader.load().copy(pythonPath = venvPy, setupComplete = true)) }
                }
            }
        }
    }

    LaunchedEffect(state.sam3dGcodeDir) {
        val dir = state.sam3dGcodeDir ?: return@LaunchedEffect
        if (state.envSetup.isActive) return@LaunchedEffect
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
                Text("Point SAM3D at your DICOM series and an output folder. The segmentation engine is " +
                    "bundled with the app, and everything runs locally.",
                    style = Carbon.type.body01, color = Carbon.theme.textSecondary)
            }

            // Only shown when auto-detection failed — an installed build stages its own engine and a
            // checkout finds pipeline/, so most users never see this. It exists because the app must
            // never dead-end: before it, a missing engine left the Setup screen with a disabled button
            // and the advice "run the app from the project root", which means nothing to someone who
            // installed from a zip. See EngineStager.
            if (state.sam3dGcodeDir.isNullOrBlank()) {
                EngineFolderField { picked ->
                    onIntent(WizardIntent.SetSam3dGcodeDir(picked))
                    scope.launch(Dispatchers.IO) {
                        runCatching { ConfigLoader.save(ConfigLoader.load().copy(sam3dGcodeDir = picked)) }
                    }
                }
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
            OutputFolderWarnings(state.outputFolderPath)

            QualitySection(selected = state.quality, status = state.dicomDownsampleStatus, onSelect = ::selectQuality)
        }
      }

      // Pinned to the bottom of the Setup screen (the workflow rail is hidden on START, so this spans
      // the full shell width and reads as a window-level bar). Runs the one-click environment setup
      // (venv + deps + checkpoint); a live progress bar while active, and gone once the env is ready.
      SetupBanner(
          state = state,
          onStart = { setup?.start() },
          onCancel = { setup?.cancel(); onIntent(WizardIntent.CancelEnvSetup) },
          onRetry = { setup?.start() },
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

/**
 * Manual escape hatch for locating the Python engine, rendered only when auto-detection came up
 * empty. Validates the pick immediately — pointing this at the wrong folder is easy, and the failure
 * would otherwise surface much later as a confusing setup error.
 */
@Composable
private fun EngineFolderField(onPicked: (String) -> Unit) {
    val c = Carbon.theme
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    fun accept(path: String) {
        typed = path
        val ok = path.isNotBlank() && EngineStager.isEngineDir(path)
        invalid = path.isNotBlank() && !ok
        if (ok) onPicked(path)
    }

    Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
        Text("Pipeline engine folder", style = Carbon.type.label01, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            CarbonTextInput(
                value = typed,
                onValueChange = ::accept,
                placeholder = "the folder containing ${EngineStager.ENGINE_MARKER}",
                modifier = Modifier.weight(1f),
            )
            CarbonButton("Browse", {
                scope.launch { showFilePicker("Select the pipeline folder", FilePickerMode.FOLDER, null)?.let(::accept) }
            }, variant = CarbonButtonVariant.TERTIARY, icon = CarbonIcons.Folder)
        }
        Text(
            if (invalid)
                "That folder doesn't contain ${EngineStager.ENGINE_MARKER}. Pick the \"pipeline\" folder itself, " +
                    "not the folder containing it."
            else
                "SAM3D couldn't find its bundled Python engine — this normally means an incomplete install. " +
                    "Reinstall the app, or point it at a copy of the pipeline folder from the SAM3D repository.",
            style = Carbon.type.helperText01,
            color = if (invalid) c.textError else c.textHelper,
        )
    }
}

/**
 * Warnings about the chosen output folder that would otherwise only surface hours into a run:
 * leftover contents from a previous run, and (on Windows) a path deep enough that the engine's
 * nested intermediates can exceed MAX_PATH.
 */
@Composable
private fun OutputFolderWarnings(outputFolderPath: String?) {
    val c = Carbon.theme
    val notEmpty by produceState(false, outputFolderPath) {
        val path = outputFolderPath
        value = path != null && withContext(Dispatchers.IO) {
            runCatching {
                val dir = Paths.get(path)
                Files.isDirectory(dir) && Files.list(dir).use { it.findAny().isPresent }
            }.getOrDefault(false)
        }
    }
    val longPath = outputFolderPath != null && OsUtils.isPathRiskyForWindows(outputFolderPath)
    if (!notEmpty && !longPath) return

    Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing02)) {
        if (notEmpty) Text(
            "This folder isn't empty. Existing files with the same names will be overwritten — pick a " +
                "fresh folder if you want to keep the previous run's results.",
            style = Carbon.type.helperText01, color = c.textHelper,
        )
        if (longPath) Text(
            "This path is long. Windows limits file paths to 260 characters and the pipeline writes " +
                "several folders deep inside it, so a run can fail partway through. Choose somewhere " +
                "shorter, like C:\\sam3d-output.",
            style = Carbon.type.helperText01, color = c.textError,
        )
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
