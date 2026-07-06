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

    /** Open [path] with the OS default application (e.g. a .log in the default text viewer). */
    fun openFile(path: Path) {
        runCatching {
            when {
                isMac() -> ProcessBuilder("open", path.toString()).start()
                isWindows() -> ProcessBuilder("cmd", "/c", "start", "", path.toString()).start()
                else -> ProcessBuilder("xdg-open", path.toString()).start()
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

    /**
     * The interpreter path inside a venv created by `python -m venv <venvDir>`. venv lays the binary
     * out differently per platform: `Scripts\python.exe` on Windows, `bin/python` everywhere else.
     * Used by the one-click environment setup to build, verify, and later run the pipeline.
     */
    fun venvPython(venvDir: Path): Path = when {
        isWindows() -> venvDir.resolve("Scripts").resolve("python.exe")
        else -> venvDir.resolve("bin").resolve("python")
    }
}
