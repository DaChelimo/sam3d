package edu.upenn.sam3d

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import edu.upenn.sam3d.process.PythonProcessManager
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
    // §11.3: seed <userDataDir>/SAM3D/config.json from the bundled template on first launch.
    ConfigLoader.ensureUserConfig()

    // §task 1: restore the last window size (falling back to 1280×800 on first launch).
    val startW = (AppConfig.windowWidth ?: DEFAULT_WIDTH)
    val startH = (AppConfig.windowHeight ?: DEFAULT_HEIGHT)

    application {
        Runtime.getRuntime().addShutdownHook(Thread {
            activeProcessManager?.cancel()
        })

        val windowState = rememberWindowState(width = startW.dp, height = startH.dp)

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
            onCloseRequest = {
                val size = windowState.size
                if (size.isSpecified) saveWindowSize(size.width.value.roundToInt(), size.height.value.roundToInt())
                exitApplication()
            },
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
            App()
        }
    }
}

private fun saveWindowSize(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    val current = ConfigLoader.load()
    if (current.windowWidth != width || current.windowHeight != height) {
        ConfigLoader.save(current.copy(windowWidth = width, windowHeight = height))
    }
}
