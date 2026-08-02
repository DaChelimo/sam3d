package edu.upenn.sam3d

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    /**
     * Where the app keeps everything it owns: config.json, logs, run reports, the Python venv, and
     * the staged engine (with its 2.4 GB checkpoint).
     *
     * On Windows this is **`%LOCALAPPDATA%`, deliberately not `%APPDATA%`**. `%APPDATA%` is the
     * *Roaming* profile, which on a domain-joined machine (exactly the lab-PC case) is synchronised to
     * a network share and frequently quota'd — putting a multi-GB venv + checkpoint there either fails
     * outright or turns every logon into a multi-minute profile sync for everyone using that machine.
     * Bulk per-machine data belongs in Local. [legacyUserDataDir] + [migrateLegacyUserDataDir] carry
     * over installs that already wrote to Roaming.
     */
    fun userDataDir(): Path = when {
        isMac() -> Path(System.getProperty("user.home"), "Library", "Application Support", "SAM3D")
        isWindows() -> Path(
            System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA") ?: System.getProperty("user.home"),
            "SAM3D",
        )
        else -> Path(
            System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config",
            "sam3d",
        )
    }

    /** The pre-move Windows location (`%APPDATA%\SAM3D`, i.e. Roaming). Null on other platforms. */
    fun legacyUserDataDir(): Path? = when {
        isWindows() -> System.getenv("APPDATA")?.let { Path(it, "SAM3D") }
        else -> null
    }

    /**
     * One-shot best-effort migration of a pre-existing `%APPDATA%\SAM3D` to `%LOCALAPPDATA%\SAM3D`.
     * Roaming and Local live on the same volume, so the whole tree (venv + checkpoint included) moves
     * with a cheap rename. If that fails for any reason we fall back to copying just the small
     * settings files — a rebuilt venv is recoverable, a corrupted half-move is not. No-op unless the
     * legacy dir exists and the new one doesn't, so it can't clobber a good install.
     */
    fun migrateLegacyUserDataDir() {
        val legacy = legacyUserDataDir() ?: return
        val target = userDataDir()
        if (legacy == target) return
        if (!Files.isDirectory(legacy) || Files.exists(target)) return
        runCatching {
            Files.createDirectories(target.parent)
            Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            // Cross-device or locked file: salvage the cheap-to-keep state and leave the rest behind.
            Files.createDirectories(target)
            for (name in SMALL_STATE_FILES) {
                val src = legacy.resolve(name)
                if (Files.exists(src)) Files.copy(src, target.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** Free bytes on the volume holding [path] (walking up to the nearest existing ancestor). 0 if unknown. */
    fun usableSpaceBytes(path: Path): Long {
        var p: Path? = path.toAbsolutePath()
        while (p != null && !Files.exists(p)) p = p.parent
        return runCatching { p?.toFile()?.usableSpace ?: 0L }.getOrDefault(0L)
    }

    /**
     * Windows refuses paths longer than 260 characters unless long-path support is enabled *and* the
     * calling program opts in — the engine's `open()`/`os.makedirs()` calls do not. The pipeline nests
     * its intermediates several levels under the output folder, so a deep output path fails hours into
     * a run. Anything over this leaves too little headroom for those children.
     */
    const val WINDOWS_SAFE_PATH_BUDGET = 150

    /** True if [path] is long enough on Windows that the engine's nested writes may exceed MAX_PATH. */
    fun isPathRiskyForWindows(path: String): Boolean =
        isWindows() && path.length > WINDOWS_SAFE_PATH_BUDGET

    private val SMALL_STATE_FILES = listOf("config.json", "reports.json")

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
