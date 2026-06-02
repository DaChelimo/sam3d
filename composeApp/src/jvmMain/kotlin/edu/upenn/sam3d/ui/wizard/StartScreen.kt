package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.AppConfig
import edu.upenn.sam3d.state.PythonStatus
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.components.FilePickerMode
import edu.upenn.sam3d.ui.components.showFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

private val SuccessGreen = Color(0xFF4CAF50.toInt())

@Composable
fun StartScreen(state: WizardState, onIntent: (WizardIntent) -> Unit) {
    val scope = rememberCoroutineScope()

    // Pre-fill from per-user config (§11.3) on first composition; fields remain editable.
    LaunchedEffect(Unit) {
        if (state.sam3dGcodeDir == null) AppConfig.sam3dGcodeDir?.let { onIntent(WizardIntent.SetSam3dGcodeDir(it)) }
        if (state.dicomFolderPath == null) AppConfig.dicomFolderPath?.let { onIntent(WizardIntent.SetDicomFolder(it)) }
        if (state.outputFolderPath == null) AppConfig.outputFolderPath?.let { onIntent(WizardIntent.SetOutputFolder(it)) }
        if (state.pythonPath == "python3") onIntent(WizardIntent.SetPythonPath(AppConfig.pythonPath))
    }

    // Check checkpoint when SAM3D dir changes
    LaunchedEffect(state.sam3dGcodeDir) {
        val dir = state.sam3dGcodeDir ?: return@LaunchedEffect
        val exists = withContext(Dispatchers.IO) {
            Files.exists(Paths.get(dir, "checkpoints", "sam_vit_h_4b8939.pth"))
        }
        onIntent(WizardIntent.SetCheckpointExists(exists))
    }

    // Run python --version when verification is triggered
    LaunchedEffect(state.pythonStatus) {
        if (state.pythonStatus != PythonStatus.CHECKING) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder(listOf(state.pythonPath, "--version"))
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor()
                if (proc.exitValue() == 0) PythonStatus.VERIFIED else PythonStatus.ERROR
            } catch (_: Exception) {
                PythonStatus.ERROR
            }
        }
        onIntent(WizardIntent.SetPythonStatus(result))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Setup", style = MaterialTheme.typography.headlineMedium)

        // SAM3D-GCODE directory
        PathPickerRow(
            label = "SAM3D-GCODE Directory",
            value = state.sam3dGcodeDir ?: "",
            placeholder = "Path to sam3d.py directory…",
            onValueChange = { onIntent(WizardIntent.SetSam3dGcodeDir(it)) },
            onBrowse = {
                scope.launch {
                    val path = showFilePicker(
                        title = "Select SAM3D-GCODE Directory",
                        mode = FilePickerMode.FOLDER,
                        initialDirectory = state.sam3dGcodeDir?.let { File(it) },
                    )
                    if (path != null) onIntent(WizardIntent.SetSam3dGcodeDir(path))
                }
            },
        )

        // DICOM folder
        PathPickerRow(
            label = "DICOM Folder",
            value = state.dicomFolderPath ?: "",
            placeholder = "Path to folder containing .dcm files…",
            onValueChange = { onIntent(WizardIntent.SetDicomFolder(it)) },
            onBrowse = {
                scope.launch {
                    val path = showFilePicker(
                        title = "Select DICOM Folder",
                        mode = FilePickerMode.FOLDER,
                        initialDirectory = state.dicomFolderPath?.let { File(it) },
                    )
                    if (path != null) onIntent(WizardIntent.SetDicomFolder(path))
                }
            },
        )

        // Output folder
        PathPickerRow(
            label = "Output Folder",
            value = state.outputFolderPath ?: "",
            placeholder = "~/Documents/SAM3D-Output",
            onValueChange = { onIntent(WizardIntent.SetOutputFolder(it)) },
            onBrowse = {
                scope.launch {
                    val path = showFilePicker(
                        title = "Select Output Folder",
                        mode = FilePickerMode.FOLDER,
                        initialDirectory = state.outputFolderPath?.let { File(it) },
                    )
                    if (path != null) onIntent(WizardIntent.SetOutputFolder(path))
                }
            },
        )

        // Python binary + Verify
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Python Binary", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.pythonPath,
                    onValueChange = { onIntent(WizardIntent.SetPythonPath(it)) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("python3") },
                    singleLine = true,
                )
                Button(
                    onClick = { onIntent(WizardIntent.VerifyPython) },
                    enabled = state.pythonStatus != PythonStatus.CHECKING,
                ) {
                    Text("Verify")
                }
                PythonStatusBadge(state.pythonStatus)
            }
        }

        // Checkpoint status
        CheckpointRow(
            sam3dGcodeDir = state.sam3dGcodeDir,
            checkpointExists = state.checkpointExists,
            onDownload = { onIntent(WizardIntent.DownloadCheckpoint) },
        )
    }
}

@Composable
private fun PathPickerRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
            Button(onClick = onBrowse) {
                Text("Browse")
            }
        }
    }
}

@Composable
private fun PythonStatusBadge(status: PythonStatus) {
    when (status) {
        PythonStatus.VERIFIED -> Text(
            "VERIFIED",
            color = SuccessGreen,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
        )
        PythonStatus.ERROR -> Text(
            "ERROR",
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
        )
        PythonStatus.CHECKING -> Text(
            "Checking…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        PythonStatus.UNCHECKED -> Spacer(Modifier.width(80.dp))
    }
}

@Composable
private fun CheckpointRow(
    sam3dGcodeDir: String?,
    checkpointExists: Boolean,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SAM Checkpoint", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                sam3dGcodeDir == null -> Text(
                    "Select SAM3D-GCODE directory first",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                checkpointExists -> {
                    Text("✓", color = SuccessGreen,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("sam_vit_h_4b8939.pth found", color = SuccessGreen)
                }
                else -> {
                    Text("✗", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Checkpoint missing", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onDownload) {
                        Text("Download checkpoint (2.5 GB)")
                    }
                }
            }
        }
    }
}
