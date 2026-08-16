package edu.upenn.sam3d

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import edu.upenn.sam3d.dicom.Dcm4cheDownsampler
import edu.upenn.sam3d.domain.model.WizardStep
import edu.upenn.sam3d.domain.usecase.SaveAnnotationsUseCase
import edu.upenn.sam3d.process.GitHubUpdateChecker
import edu.upenn.sam3d.process.PythonPipelineRunner
import edu.upenn.sam3d.process.PythonProcessManager
import edu.upenn.sam3d.process.RunReportStore
import edu.upenn.sam3d.state.WizardViewModel
import edu.upenn.sam3d.ui.components.CarbonButton
import edu.upenn.sam3d.ui.components.CarbonButtonSize
import edu.upenn.sam3d.ui.components.CarbonButtonVariant
import edu.upenn.sam3d.ui.components.CarbonIcons
import edu.upenn.sam3d.ui.theme.AppTheme
import edu.upenn.sam3d.ui.theme.Carbon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.awt.Dimension
import kotlin.math.roundToInt

// Holds the active pipeline process manager so the shutdown hook can forcibly cancel it.
// Set by the pipeline executor in Phase 5; null until a run is started.
@Volatile
internal var activeProcessManager: PythonProcessManager? = null

private const val DEFAULT_WIDTH = 1280
private const val DEFAULT_HEIGHT = 800

fun main() {
    // Route setup's downloads (uv, Python, packages, the checkpoint) through whatever proxy the OS is
    // configured to use. Managed university networks — the deployment target — usually require one,
    // and without this every download fails with an opaque timeout on an otherwise online machine.
    System.setProperty("java.net.useSystemProxies", "true")

    // Windows: carry an existing %APPDATA%\SAM3D (Roaming) install over to %LOCALAPPDATA%. Must run
    // before anything reads config or resolves the data dir. No-op everywhere else.
    OsUtils.migrateLegacyUserDataDir()

    // §11.3: seed <userDataDir>/SAM3D/config.json from the bundled template on first launch.
    ConfigLoader.ensureUserConfig()

    // §task 1: restore the last window size (falling back to 1280×800 on first launch).
    val startW = (AppConfig.windowWidth ?: DEFAULT_WIDTH)
    val startH = (AppConfig.windowHeight ?: DEFAULT_HEIGHT)

    application {
        Runtime.getRuntime().addShutdownHook(Thread {
            activeProcessManager?.cancel()
        })

        // The ViewModel is owned here (not inside App) so this window can observe busy state and warn
        // before a close throws away an in-flight checkpoint download or a running pipeline.
        // SaveAnnotationsUseCase writes tempdir/points.json; PythonPipelineRunner spawns sam3d.py and
        // registers the live process with the shutdown hook above (activeProcessManager).
        val viewModel = remember {
            val runner = PythonPipelineRunner(
                logDir = OsUtils.userDataDir().resolve("logs"),
                onManagerStarted = { activeProcessManager = it },
            )
            WizardViewModel(
                annotationSaver = SaveAnnotationsUseCase(),
                pipelineRunner = runner,
                dicomDownsampler = Dcm4cheDownsampler(),
                reportStore = RunReportStore(),
                updateSource = GitHubUpdateChecker(),
            )
        }
        val uiState by viewModel.state.collectAsState()
        // "Busy" = work that closing would silently throw away: an active checkpoint download, or a
        // pipeline that's still processing (not yet errored/done).
        val isBusy = uiState.checkpointDownload.isActive ||
            (uiState.currentStep == WizardStep.PROCESSING && uiState.error == null)

        var showCloseConfirm by remember { mutableStateOf(false) }

        val windowState = rememberWindowState(width = startW.dp, height = startH.dp)

        // Save the current size, then quit. The shutdown hook tears down any live subprocess; an
        // in-flight download's coroutine dies with the JVM and its .part file is never promoted.
        val saveAndExit = {
            val size = windowState.size
            if (size.isSpecified) saveWindowSize(size.width.value.roundToInt(), size.height.value.roundToInt())
            exitApplication()
        }

        // Persist size changes (debounced via collectLatest) so a restart restores the layout. Loads
        // the full config and copies only the size, leaving the user's path/env keys untouched.
        LaunchedEffect(Unit) {
            snapshotFlow { windowState.size }.collectLatest { size ->
                delay(500) // settle: collectLatest cancels this if another resize arrives first
                if (size.isSpecified) withContext(Dispatchers.IO) {
                    saveWindowSize(size.width.value.roundToInt(), size.height.value.roundToInt())
                }
            }
        }

        Window(
            // If something's running, intercept the close and confirm; otherwise quit straight away.
            onCloseRequest = { if (isBusy) showCloseConfirm = true else saveAndExit() },
            title = "SAM3D",
            state = windowState,
        ) {
            window.minimumSize = Dimension(960, 600)
            // macOS: make the native title bar transparent and let content fill the window, so the
            // white system bar becomes the app's dark canvas (App adds a top inset for the controls).
            if (OsUtils.isMac()) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
            App(viewModel)

            if (showCloseConfirm) {
                AppTheme {
                    CloseConfirmDialog(
                        downloading = uiState.checkpointDownload.isActive,
                        onConfirm = saveAndExit,
                        onDismiss = { showCloseConfirm = false },
                    )
                }
            }
        }
    }
}

/**
 * Guards an accidental quit while long-running work is in flight. [downloading] picks the wording:
 * a checkpoint download (re-startable) vs. a running pipeline (progress discarded). Styled to match
 * the app's error modal (Carbon colours, square corners).
 */
@Composable
private fun CloseConfirmDialog(downloading: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Carbon.theme
    val title = if (downloading) "Stop the checkpoint download?" else "Stop the running pipeline?"
    val body = if (downloading)
        "A 2.4 GB checkpoint download is in progress. Closing now will stop it, and you'll have to download it again next time."
    else
        "The pipeline is still running. Closing now will stop it and discard progress — no G-code will be produced."
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = c.layer01,
        title = { Text(title, style = Carbon.type.headingCompact02, color = c.textPrimary) },
        text = {
            Box(Modifier.widthIn(max = 480.dp)) {
                Text(body, style = Carbon.type.body01, color = c.textSecondary)
            }
        },
        confirmButton = {
            CarbonButton(
                "Close anyway", onConfirm,
                variant = CarbonButtonVariant.DANGER, size = CarbonButtonSize.MD, icon = CarbonIcons.Close,
            )
        },
        dismissButton = {
            CarbonButton(
                "Keep running", onDismiss,
                variant = CarbonButtonVariant.GHOST, size = CarbonButtonSize.MD,
            )
        },
    )
}

private fun saveWindowSize(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    val current = ConfigLoader.load()
    if (current.windowWidth != width || current.windowHeight != height) {
        ConfigLoader.save(current.copy(windowWidth = width, windowHeight = height))
    }
}
