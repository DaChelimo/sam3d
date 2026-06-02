package edu.upenn.sam3d

import java.nio.file.Path
import kotlin.io.path.Path

/** OS-specific helpers (§6.4): reveal a file in the native browser, locate the user data dir. */
object OsUtils {
    private val os = System.getProperty("os.name").lowercase()

    fun isMac(): Boolean = os.contains("mac")
    fun isWindows(): Boolean = os.contains("win")

    /** Reveal [path] in Finder/Explorer/Files. Best-effort; falls back to the parent dir on Linux. */
    fun revealInFileBrowser(path: Path) {
        runCatching {
            when {
                isMac() -> ProcessBuilder("open", "-R", path.toString()).start()
                isWindows() -> ProcessBuilder("explorer", "/select,${path}").start()
                else -> ProcessBuilder("xdg-open", (path.parent ?: path).toString()).start()
            }
        }
    }

    fun userDataDir(): Path = when {
        isMac() -> Path(System.getProperty("user.home"), "Library", "Application Support", "SAM3D")
        isWindows() -> Path(System.getenv("APPDATA") ?: System.getProperty("user.home"), "SAM3D")
        else -> Path(
            System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config",
            "sam3d",
        )
    }
}
