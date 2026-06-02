package edu.upenn.sam3d

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import edu.upenn.sam3d.process.PythonProcessManager
import java.awt.Dimension

// Holds the active pipeline process manager so the shutdown hook can forcibly cancel it.
// Set by the pipeline executor in Phase 5; null until a run is started.
@Volatile
internal var activeProcessManager: PythonProcessManager? = null

fun main() {
    // §11.3: seed <userDataDir>/SAM3D/config.json from the bundled template on first launch.
    ConfigLoader.ensureUserConfig()
    application {
        Runtime.getRuntime().addShutdownHook(Thread {
            activeProcessManager?.cancel()
        })

        Window(
            onCloseRequest = ::exitApplication,
            title = "SAM3D",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            window.minimumSize = Dimension(960, 600)
            App()
        }
    }
}
