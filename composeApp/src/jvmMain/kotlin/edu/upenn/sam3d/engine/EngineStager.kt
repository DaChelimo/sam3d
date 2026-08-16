package edu.upenn.sam3d.engine

import edu.upenn.sam3d.OsUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/**
 * Resolves the Python engine directory the app runs against, and — for installed builds — stages a
 * **writable** copy of it into the user data dir.
 *
 * ### Why this exists
 * Before this, the engine was found only by walking up from the working directory looking for
 * `pipeline/sam3d.py` ([findInWorkingDirAncestors]). That works when the app is launched by Gradle
 * from a checkout and fails for **every packaged install** — the `.msi`/`.dmg`/portable zip contain
 * only `SAM3D.exe` + `runtime/` + `app/`, so users saw "The bundled pipeline/ folder wasn't found —
 * run the app from the project root", which is unactionable advice when there is no project root.
 * The engine is now shipped as an app resource (see composeApp/build.gradle.kts `stageEngineResources`).
 *
 * ### Why a *copy* rather than running the resource in place
 * The app writes into the engine directory: the 2.4 GB checkpoint lands in `checkpoints/`,
 * `SaveAnnotationsUseCase` writes `tempdir/points.json`, and `sam3d.py` runs with its cwd set there.
 * An installed app's resource directory is `C:\Program Files\SAM3D\app\resources` (or an app bundle
 * on macOS) — read-only for a standard user, so setup would run for twenty minutes and then fail on
 * the checkpoint write. Staging into the user data dir keeps every write in a place the user owns.
 * It costs ~150 KB: only `.py` sources and `requirements.txt` are copied, never the checkpoint.
 *
 * ### Dev checkouts are left alone
 * When the app runs from a repo checkout there is no packaged resource dir, so we use `pipeline/`
 * in place — the checkpoint continues to live at `pipeline/checkpoints/` exactly as the README and
 * `.gitignore` describe, and nothing about the development loop changes.
 */
object EngineStager {

    /** Marks a staged copy so we can tell which app build produced it and re-stage after an update. */
    private const val STAMP_FILE = ".staged-from"

    /** The system property Compose Desktop sets to the app's resources dir in a packaged build. */
    private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"

    /** Subdirectory of the app resources holding the vendored engine (see the Gradle staging task). */
    private const val ENGINE_RESOURCE_DIR = "engine"

    /** The one file that proves a directory really is the engine and not an empty leftover. */
    const val ENGINE_MARKER = "sam3d.py"

    /**
     * The engine directory to run against, or null if no engine can be found at all (a broken
     * install — the Setup screen then offers a manual folder picker rather than dead-ending).
     *
     * Dev checkout → the repo's `pipeline/`, used in place.
     * Packaged install → stage the shipped resource into the user data dir and return that.
     *
     * The checkout probe runs **first** deliberately. Gradle's `run` task also sets the app-resources
     * system property, so checking that first would make a developer's run stage into the user data
     * dir and re-download the 2.4 GB checkpoint it already has at `pipeline/checkpoints/`. Finding a
     * checkout is unambiguous evidence we're not an installed build, so it wins.
     */
    fun resolve(userDataDir: Path = OsUtils.userDataDir()): String? {
        findInWorkingDirAncestors()?.let { return it.toString() }
        packagedEngineResource()?.let { source ->
            runCatching { return stage(source, userDataDir.resolve(ENGINE_RESOURCE_DIR)).toString() }
        }
        return null
    }

    /** The engine shipped inside a packaged app, or null when running from a checkout. */
    fun packagedEngineResource(): Path? {
        val root = System.getProperty(RESOURCES_DIR_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
        val dir = Path.of(root, ENGINE_RESOURCE_DIR)
        return dir.takeIf { it.resolve(ENGINE_MARKER).exists() }
    }

    /**
     * Search for a checkout's `pipeline/` by walking UP from the working directory. `./gradlew
     * :composeApp:run` sets cwd to the **module** dir (`composeApp/`), not the repo root — so we check
     * `composeApp/pipeline` (absent), then `../pipeline` (the real one), and so on.
     */
    fun findInWorkingDirAncestors(start: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath()): Path? {
        var dir: Path? = start
        repeat(6) {
            val d = dir ?: return null
            val candidate = d.resolve("pipeline")
            if (candidate.resolve(ENGINE_MARKER).exists()) return candidate
            dir = d.parent
        }
        return null
    }

    /**
     * Copy the engine sources from [source] into [target], returning [target].
     *
     * Idempotent and update-aware: a file is re-copied only when its size or timestamp differs, so
     * relaunching is nearly free while an app update refreshes the sources. Runtime state already in
     * [target] — `checkpoints/` above all, but also `tempdir/` and `outputs/` — is **never** touched,
     * so upgrading the app doesn't cost the user a 2.4 GB re-download.
     */
    fun stage(source: Path, target: Path): Path {
        Files.createDirectories(target)
        Files.newDirectoryStream(source).use { entries ->
            for (entry in entries) {
                if (!Files.isRegularFile(entry)) continue           // engine sources are flat; skip dirs
                val dest = target.resolve(entry.fileName.toString())
                if (isUpToDate(entry, dest)) continue
                Files.copy(entry, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
        // The engine resolves these relatively from its cwd and assumes they exist.
        Files.createDirectories(target.resolve("checkpoints"))
        runCatching { Files.writeString(target.resolve(STAMP_FILE), source.toString()) }
        return target
    }

    private fun isUpToDate(source: Path, dest: Path): Boolean = runCatching {
        Files.exists(dest) &&
            Files.size(dest) == Files.size(source) &&
            Files.getLastModifiedTime(dest) >= Files.getLastModifiedTime(source)
    }.getOrDefault(false)

    /** True if [dir] looks like a usable engine — what the manual folder picker validates against. */
    fun isEngineDir(dir: String): Boolean =
        runCatching { Path.of(dir).resolve(ENGINE_MARKER).exists() }.getOrDefault(false)
}
