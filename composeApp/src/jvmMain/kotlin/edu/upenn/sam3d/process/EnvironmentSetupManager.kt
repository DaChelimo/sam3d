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
) {
    private val _state = MutableStateFlow<EnvSetup>(EnvSetup.Idle)
    val state: StateFlow<EnvSetup> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var process: Process? = null

    private val recentLines = ArrayDeque<String>()
    @Volatile private var logWriter: BufferedWriter? = null
    @Volatile private var logFilePath: Path? = null

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

    private fun onOuterFailure(e: Throwable) {
        closeLog()
        // Cancellation is a user action, not an error. Per-stage failures already set EnvSetup.Failed.
        if (e is CancellationException) { if (_state.value.isActive) _state.value = EnvSetup.Idle }
    }

    private suspend fun run() {
        openLog()
        try {
            // 1. uv — the tool that installs Python and manages the venv. Idempotent (skips if present).
            _state.value = EnvSetup.PreparingUv
            val uv = try {
                uvInstaller.ensure().toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(EnvSetup.Stage.UV, "Couldn't download the setup tool (uv): ${e.message ?: "unknown error"}. Check your internet connection and retry."); return
            }

            // 2. Python — uv downloads a managed CPython 3.11. No system Python required.
            _state.value = EnvSetup.InstallingPython
            val pyCode = runProcess(listOf(uv, "python", "install", PYTHON_VERSION)) {}
            if (pyCode != 0) { fail(EnvSetup.Stage.PYTHON, "Couldn't install Python $PYTHON_VERSION (uv exited $pyCode). See the log for details."); return }

            // 3. venv — skip a valid one (resume); otherwise (re)create from scratch with the managed Python.
            val venvPy = OsUtils.venvPython(venvDir)
            if (!venvValid(venvPy)) {
                _state.value = EnvSetup.CreatingVenv
                runCatching { venvDir.toFile().deleteRecursively() }
                Files.createDirectories(venvDir.parent)
                val code = runProcess(listOf(uv, "venv", venvDir.toString(), "--python", PYTHON_VERSION)) {}
                if (code != 0) { fail(EnvSetup.Stage.VENV, "Couldn't create the virtual environment (uv venv exited $code). See the log for details."); return }
            }

            // 4. install deps — idempotent + resumable (uv skips satisfied packages, reuses its cache).
            // EXTRA_DEPS covers packages sam3d.py hard-requires at import time but which requirements.txt
            // (vendored, read-only) omits — notably a Qt binding for `matplotlib.use('qtagg')` at
            // sam3d.py:40, which crashes any pure-requirements.txt env on startup. We install them
            // alongside so the engine actually runs; we never edit the vendored requirements file.
            _state.value = EnvSetup.InstallingDeps("Resolving dependencies…")
            val reqs = Path.of(pipelineDir, "requirements.txt")
            val installCode = runProcess(
                listOf(uv, "pip", "install", "--python", venvPy.toString(), "-r", reqs.toString()) + EXTRA_DEPS,
            ) { line -> _state.value = EnvSetup.InstallingDeps(line) }
            if (installCode != 0) { fail(EnvSetup.Stage.INSTALL, installFailureMessage()); return }

            // 5. checkpoint — resumes from its .part; reuses the native downloader.
            _state.value = EnvSetup.DownloadingCheckpoint(0, null)
            try {
                checkpoint.download(Path.of(pipelineDir, *CheckpointDownloader.CHECKPOINT_REL)) { received, total ->
                    _state.value = EnvSetup.DownloadingCheckpoint(received, total)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(EnvSetup.Stage.CHECKPOINT, "The SAM checkpoint download failed: ${e.message ?: "unknown error"}. It will resume where it left off on retry."); return
            }

            // 6. verify — the venv can import the engine's key packages AND load the Qt backend the way
            // sam3d.py does (matplotlib.use('qtagg')) AND the checkpoint is complete. The qtagg check is
            // what surfaces a missing Qt binding here at setup rather than as a startup crash on first run.
            _state.value = EnvSetup.Verifying
            val importCode = runProcess(
                listOf(venvPy.toString(), "-c",
                    "import torch, segment_anything, open3d, cv2, pydicom, numpy, scipy; " +
                        "import matplotlib; matplotlib.use('qtagg')"),
            ) {}
            if (importCode != 0) {
                fail(EnvSetup.Stage.VERIFY, "The environment was built but a required package failed to import. See the log for details."); return
            }
            val ckpt = Path.of(pipelineDir, *CheckpointDownloader.CHECKPOINT_REL)
            if (!Files.exists(ckpt) || Files.size(ckpt) < MIN_CHECKPOINT_BYTES) {
                fail(EnvSetup.Stage.CHECKPOINT, "The checkpoint file is missing or incomplete. Retry to finish the download."); return
            }

            _state.value = EnvSetup.Succeeded
        } finally {
            closeLog()
        }
    }

    /** Run [cmd] merging stdout/stderr, streaming each line to [onLine] + the log; returns the exit code. */
    private suspend fun runProcess(cmd: List<String>, onLine: (String) -> Unit): Int {
        val proc = ProcessBuilder(cmd)
            .directory(java.io.File(pipelineDir))
            .redirectErrorStream(true)
            .start()
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
        private const val RECENT_CAP = 40
        /** A complete ViT-H checkpoint is ~2.4 GB; treat anything under 1 GB as a truncated `.part`. */
        private const val MIN_CHECKPOINT_BYTES = 1_000_000_000L
    }
}
