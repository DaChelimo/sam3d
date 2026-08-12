package edu.upenn.sam3d.process

import edu.upenn.sam3d.AppConfig
import edu.upenn.sam3d.OsUtils
import edu.upenn.sam3d.state.EnvSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

/**
 * The one-click **environment setup**: installs Python, builds a virtual environment, installs the
 * pipeline's dependencies, and downloads the SAM checkpoint — as one cancelable, resumable flow,
 * driven by [`uv`](https://github.com/astral-sh/uv). Because uv installs its own managed CPython, the
 * app needs **no system Python** and there's no version field to fill in. Mirrors
 * [PythonProcessManager]/[CheckpointDownloader]'s shape: a [StateFlow] of [EnvSetup] plus [start]/[cancel].
 *
 * **Resumability** is by disk probing, not a saved cursor: a working uv/venv is skipped, `uv python
 * install` and `uv pip install` are idempotent (skip what's done, reuse caches), and the checkpoint
 * resumes from its `.part`. So killing the app mid-setup and relaunching just continues.
 *
 * @param pipelineDir the vendored engine dir (holds requirements.txt + checkpoints/); AppConfig resolves it.
 * @param venvDir where the venv is built — the app data dir, NOT inside pipelineDir, so the engine stays pristine.
 */
class EnvironmentSetupManager(
    private val pipelineDir: String,
    private val venvDir: Path = AppConfig.venvDir,
    private val logDir: Path = OsUtils.userDataDir().resolve("logs"),
    private val uvInstaller: UvInstaller = UvInstaller(),
    private val checkpoint: CheckpointDownloader = CheckpointDownloader(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Completeness gate for the downloaded checkpoint; lowered only by the CI smoke test. */
    private val minCheckpointBytes: Long = MIN_CHECKPOINT_BYTES,
) {
    private val _state = MutableStateFlow<EnvSetup>(EnvSetup.Idle)
    val state: StateFlow<EnvSetup> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var process: Process? = null

    private val recentLines = ArrayDeque<String>()
    @Volatile private var logWriter: BufferedWriter? = null
    @Volatile private var logFilePath: Path? = null

    /** The stage currently running — so an unexpected throw can be reported against the right step. */
    @Volatile private var currentStage: EnvSetup.Stage = EnvSetup.Stage.UV

    /** The interpreter a completed setup produces — what the app then runs the pipeline with. */
    fun venvPythonPath(): String = OsUtils.venvPython(venvDir).toString()

    /** Kick off (or resume) setup. */
    fun start() {
        job?.cancel()
        checkpoint.cancel()
        _state.value = EnvSetup.PreparingUv
        job = scope.launch { runCatching { run() }.onFailure(::onOuterFailure) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        runCatching { process?.destroyForcibly() }
        process = null
        checkpoint.cancel()
        closeLog()
        if (_state.value.isActive) _state.value = EnvSetup.Idle
    }

    /**
     * Terminal handler for anything [run] throws.
     *
     * This used to swallow every non-cancellation throwable, which left [_state] parked on whatever
     * stage was in flight — so an `IOException` from `ProcessBuilder` (uv.exe quarantined by
     * antivirus, the engine directory gone, the disk full) showed the user a progress bar that span
     * forever with no error, no log pointer, and no Retry. A stuck spinner is the single worst
     * failure mode for a remote user: it isn't reportable. Every path now lands on a terminal state.
     */
    private fun onOuterFailure(e: Throwable) {
        closeLog()
        // Cancellation is a user action, not an error.
        if (e is CancellationException) {
            if (_state.value.isActive) _state.value = EnvSetup.Idle
            return
        }
        // Per-stage failures already set EnvSetup.Failed; only fill in the ones that escaped.
        if (_state.value !is EnvSetup.Failed) {
            fail(currentStage, "Setup stopped unexpectedly: ${describe(e)}. See the log for details, then retry.")
        }
    }

    /** A message worth showing a user: exception messages alone are often empty or just a path. */
    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown error"

    private suspend fun run() {
        openLog()
        try {
            // 0. preflight — the two things that make every later stage fail confusingly if wrong:
            // a missing engine directory, and not enough disk for a multi-GB install.
            currentStage = EnvSetup.Stage.UV
            val engine = Path.of(pipelineDir)
            if (!Files.isDirectory(engine)) {
                fail(EnvSetup.Stage.UV, "The pipeline engine folder is missing ($pipelineDir). Reinstall the app, or pick the engine folder on the Setup screen."); return
            }
            val free = OsUtils.usableSpaceBytes(venvDir)
            if (free in 1 until AppConfig.MIN_FREE_BYTES_FOR_SETUP) {
                fail(
                    EnvSetup.Stage.UV,
                    "Not enough free disk space: setup needs about ${gb(AppConfig.MIN_FREE_BYTES_FOR_SETUP)} " +
                        "(Python, PyTorch, and the 2.4 GB model checkpoint) but only ${gb(free)} is available " +
                        "on this drive. Free up space and retry.",
                )
                return
            }

            // 1. uv — the tool that installs Python and manages the venv. Idempotent (skips if present).
            _state.value = EnvSetup.PreparingUv
            val uv = try {
                uvInstaller.ensure().toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(EnvSetup.Stage.UV, "Couldn't download the setup tool (uv): ${describe(e)}.${NETWORK_HINT}"); return
            }

            // 2. Python — uv downloads a managed CPython 3.11. No system Python required.
            currentStage = EnvSetup.Stage.PYTHON
            _state.value = EnvSetup.InstallingPython
            val pyCode = runProcess(listOf(uv, "python", "install", PYTHON_VERSION)) {}
            if (pyCode != 0) { fail(EnvSetup.Stage.PYTHON, "Couldn't install Python $PYTHON_VERSION (uv exited $pyCode). See the log for details."); return }

            // 3. venv — skip a valid one (resume); otherwise (re)create from scratch with the managed Python.
            currentStage = EnvSetup.Stage.VENV
            val venvPy = OsUtils.venvPython(venvDir)
            if (!venvValid(venvPy)) {
                _state.value = EnvSetup.CreatingVenv
                runCatching { venvDir.toFile().deleteRecursively() }
                Files.createDirectories(venvDir.parent)
                val code = runProcess(listOf(uv, "venv", venvDir.toString(), "--python", PYTHON_VERSION)) {}
                if (code != 0) { fail(EnvSetup.Stage.VENV, "Couldn't create the virtual environment (uv venv exited $code). See the log for details."); return }
            }

            // 4. install deps — idempotent + resumable (uv skips satisfied packages, reuses its cache).
            //
            // We install from a *derived* requirements file, not the vendored one: see
            // EngineRequirements for why (the vendored `git+…` SAM URL needs a `git` executable that a
            // clean Windows machine doesn't have). The same pass folds in EXTRA_DEPS — packages
            // sam3d.py hard-requires at import time but requirements.txt omits, notably a Qt binding
            // for `matplotlib.use('qtagg')` (sam3d.py:40), which crashes any pure-requirements.txt env
            // on startup. The vendored file itself is never touched.
            currentStage = EnvSetup.Stage.INSTALL
            _state.value = EnvSetup.InstallingDeps("Resolving dependencies…")
            val reqs = try {
                EngineRequirements.materialize(
                    source = Path.of(pipelineDir, "requirements.txt"),
                    dest = venvDir.resolveSibling("requirements.generated.txt"),
                    extras = EXTRA_DEPS,
                )
            } catch (e: Exception) {
                fail(EnvSetup.Stage.INSTALL, "Couldn't read the engine's requirements.txt: ${describe(e)}."); return
            }
            val installCode = runProcess(
                listOf(uv, "pip", "install", "--python", venvPy.toString(), "-r", reqs.toString()),
            ) { line -> _state.value = EnvSetup.InstallingDeps(line) }
            if (installCode != 0) { fail(EnvSetup.Stage.INSTALL, installFailureMessage()); return }

            // 5. checkpoint — resumes from its .part; reuses the native downloader.
            currentStage = EnvSetup.Stage.CHECKPOINT
            _state.value = EnvSetup.DownloadingCheckpoint(0, null)
            try {
                checkpoint.download(Path.of(pipelineDir, *CheckpointDownloader.CHECKPOINT_REL)) { received, total ->
                    _state.value = EnvSetup.DownloadingCheckpoint(received, total)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(EnvSetup.Stage.CHECKPOINT, "The SAM checkpoint download failed: ${describe(e)}. It will resume where it left off on retry.$NETWORK_HINT"); return
            }

            // 6. verify — the venv can import everything the engine imports, load the Qt backend the way
            // sam3d.py does (matplotlib.use('qtagg')), and the checkpoint is complete.
            //
            // The import list is the engine's *actual* third-party surface (see VERIFY_IMPORTS), not a
            // sample of it. A partial list lets setup report "ready", unlock Continue, and then have the
            // run die seconds after launch on a missing import — with the user staring at a green
            // checkmark. tkinter matters most here: it isn't pip-installable, some Python builds ship
            // without it, and `import post_processing_windows` (sam3d.py:33) pulls it in unconditionally.
            currentStage = EnvSetup.Stage.VERIFY
            _state.value = EnvSetup.Verifying
            val missing = mutableListOf<String>()
            for (module in VERIFY_IMPORTS) {
                val code = runProcess(listOf(venvPy.toString(), "-c", "import $module"), onLine = {})
                if (code != 0) missing += module
            }
            val qtCode = runProcess(
                listOf(venvPy.toString(), "-c", "import matplotlib; matplotlib.use('qtagg')"),
                onLine = {},
            )
            if (qtCode != 0) missing += "matplotlib (Qt backend)"
            if (missing.isNotEmpty()) {
                fail(EnvSetup.Stage.VERIFY, verifyFailureMessage(missing)); return
            }
            val ckpt = Path.of(pipelineDir, *CheckpointDownloader.CHECKPOINT_REL)
            if (!Files.exists(ckpt) || Files.size(ckpt) < minCheckpointBytes) {
                fail(EnvSetup.Stage.CHECKPOINT, "The checkpoint file is missing or incomplete. Retry to finish the download."); return
            }

            _state.value = EnvSetup.Succeeded
        } finally {
            closeLog()
        }
    }

    /**
     * Run [cmd] merging stdout/stderr, streaming each line to [onLine] + the log; returns the exit code.
     *
     * The [PythonEnv] variables matter most for the VERIFY stage, which decides whether setup passes.
     * It probes the venv with `python -c "import <module>"`, and on Windows a module that merely
     * *warns* with a non-ASCII character (a curly quote in a deprecation notice is enough) would die
     * encoding that warning to a cp1252 pipe, exit non-zero, and be reported as missing — failing
     * setup with "A Python dependency is missing" on an environment that is in fact complete.
     */
    private suspend fun runProcess(cmd: List<String>, onLine: (String) -> Unit): Int {
        val builder = ProcessBuilder(cmd)
            .directory(java.io.File(pipelineDir))
            .redirectErrorStream(true)
        PythonEnv.apply(builder.environment())
        val proc = builder.start()
        process = proc
        try {
            proc.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    coroutineContext.ensureActive()  // cooperative cancellation between lines
                    remember(line)
                    logWriter?.let { runCatching { it.write(line); it.newLine() } }
                    onLine(line)
                }
            }
            return proc.waitFor()
        } catch (t: Throwable) {
            runCatching { proc.destroyForcibly() }  // kill uv/pip so it doesn't keep running after cancel
            throw t
        } finally {
            if (process === proc) process = null
        }
    }

    private fun venvValid(venvPy: Path): Boolean {
        if (!Files.exists(venvPy)) return false
        return runCatching {
            val p = ProcessBuilder(venvPy.toString(), "--version").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
            p.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun fail(stage: EnvSetup.Stage, message: String) {
        _state.value = EnvSetup.Failed(stage, message, logFilePath?.toString())
    }

    private fun installFailureMessage(): String {
        val tail = synchronized(recentLines) { recentLines.toList() }
            .takeLast(6).joinToString(" ") { it.trim() }.trim()
        val detail = if (tail.isNotEmpty()) " Last output: $tail" else ""
        return "Installing the Python dependencies failed.$detail See the log for the full output, then retry (it resumes)."
    }

    /**
     * Name what's missing rather than saying "a required package failed to import". On Windows the
     * overwhelmingly common cause for the compiled packages is a missing Visual C++ runtime, which
     * produces an unhelpful "DLL load failed" deep in the log — so say so where the user will see it.
     */
    private fun verifyFailureMessage(missing: List<String>): String {
        val names = missing.joinToString(", ")
        val vcHint = if (OsUtils.isWindows() && missing.any { it in NEEDS_VC_RUNTIME })
            " On Windows this usually means the Microsoft Visual C++ Redistributable is missing — " +
                "install it from Microsoft, then retry."
        else ""
        return "The environment was built but these couldn't be imported: $names.$vcHint " +
            "See the log for details, then retry."
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)

    @Synchronized private fun remember(line: String) {
        recentLines.addLast(line)
        while (recentLines.size > RECENT_CAP) recentLines.removeFirst()
    }

    private fun openLog() {
        closeLog()
        synchronized(recentLines) { recentLines.clear() }
        runCatching {
            Files.createDirectories(logDir)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val file = logDir.resolve("env-setup-$stamp.log")
            logFilePath = file
            logWriter = Files.newBufferedWriter(file).also {
                it.write("Environment setup via uv (Python $PYTHON_VERSION)"); it.newLine()
            }
        }
    }

    private fun closeLog() {
        logWriter?.let { runCatching { it.flush(); it.close() } }
        logWriter = null
    }

    companion object {
        /** The CPython version uv installs and builds the venv from — the pipeline's tested version. */
        const val PYTHON_VERSION = "3.11"

        /**
         * Packages the engine imports at runtime but that pipeline/requirements.txt (read-only) omits.
         * PySide6 supplies the Qt binding `matplotlib.use('qtagg')` (sam3d.py:40) needs — without it the
         * engine crashes on startup with "Failed to import any of … PyQt6, PySide6, …". PySide6 is chosen
         * over PyQt for its LGPL license and reliable cross-platform wheels.
         */
        val EXTRA_DEPS = listOf("PySide6")

        /**
         * Every third-party module the engine imports, collected from the `import` statements across
         * every Python source in `pipeline/`. Verification checks all of them one at a time so the
         * failure message can name the offender.
         *
         * `tkinter` is in here despite being part of the standard library: it's a compiled extension
         * that some Python distributions omit, it cannot be repaired with `pip install`, and
         * `sam3d.py` imports it transitively at module scope via `post_processing_windows`. Catching
         * that here is the difference between a clear setup error and a crash on first run.
         */
        val VERIFY_IMPORTS = listOf(
            "numpy", "scipy", "cv2", "torch", "torchvision", "segment_anything",
            "pydicom", "SimpleITK", "nibabel", "PIL", "mrcfile", "open3d", "fastkde",
            "tqdm", "tkinter",
        )

        /** Modules whose Windows wheels are compiled and fail with "DLL load failed" without the VC++ runtime. */
        private val NEEDS_VC_RUNTIME = setOf("cv2", "open3d", "torch", "SimpleITK")

        /**
         * Appended to every download failure. University networks are the deployment target and they
         * routinely sit behind an authenticating proxy or block the two hosts setup depends on
         * (GitHub's release CDN and `dl.fbaipublicfiles.com`), which otherwise looks like a random
         * timeout.
         */
        const val NETWORK_HINT =
            " Check your internet connection — and if you're on a managed or university network, note " +
                "that setup needs access to github.com and dl.fbaipublicfiles.com, which some networks " +
                "block. Retrying resumes where it left off."

        private const val RECENT_CAP = 40
        /** A complete ViT-H checkpoint is ~2.4 GB; treat anything under 1 GB as a truncated `.part`. */
        private const val MIN_CHECKPOINT_BYTES = 1_000_000_000L
    }
}
