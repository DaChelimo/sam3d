package edu.upenn.sam3d.state

/**
 * UI-facing state of the one-click **environment setup** — the combined "install Python, build a
 * virtual environment, install the pipeline's dependencies, and download the SAM checkpoint" flow that
 * replaces the old manual conda/pip dance. Lives in commonMain (no java.*); the work is done by
 * jvmMain's `EnvironmentSetupManager`, driven by [`uv`](https://github.com/astral-sh/uv), which pushes
 * these values into [WizardState.envSetup].
 *
 * Because setup uses `uv`, the app needs **no system Python** — uv downloads a managed CPython 3.11
 * itself. The stages are ordered and each is self-checking, so an interrupted setup **resumes** on
 * relaunch: uv/Python installs are idempotent, `uv pip install` skips satisfied packages, and the
 * checkpoint resumes from its `.part`. Mirrors [CheckpointDownload]'s shape (a small sealed model with
 * an [isActive] flag) so the banner UI can drive off it the same way.
 */
sealed interface EnvSetup {
    /** Nothing happening (default, and the resting state after cancel). */
    object Idle : EnvSetup

    /** Downloading the `uv` tool itself (a one-time ~35 MB fetch). */
    object PreparingUv : EnvSetup

    /** Running `uv python install 3.11` — uv downloads a managed CPython. */
    object InstallingPython : EnvSetup

    /** Running `uv venv` (only when no valid venv already exists). */
    object CreatingVenv : EnvSetup

    /** Streaming `uv pip install -r requirements.txt`. [line] is the latest output line (a live tail). */
    data class InstallingDeps(val line: String) : EnvSetup

    /** Streaming the 2.4 GB checkpoint. [totalBytes] is null when the server omits Content-Length. */
    data class DownloadingCheckpoint(val receivedBytes: Long, val totalBytes: Long?) : EnvSetup {
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }
                ?.let { (receivedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    }

    /** Probing that the venv can import the engine's key packages and the checkpoint is in place. */
    object Verifying : EnvSetup

    /** Everything is ready: Python + venv built, deps importable, checkpoint present. */
    object Succeeded : EnvSetup

    /** A stage failed; [message] is safe (and actionable) to show the user. [logPath] may hold detail. */
    data class Failed(val stage: Stage, val message: String, val logPath: String? = null) : EnvSetup

    /** Which stage a [Failed] happened in — lets the UI tailor the retry verb / hint. */
    enum class Stage { UV, PYTHON, VENV, INSTALL, CHECKPOINT, VERIFY }

    /** True while any long-running stage is in flight (drives the Cancel affordance). */
    val isActive: Boolean
        get() = this is PreparingUv || this is InstallingPython || this is CreatingVenv ||
            this is InstallingDeps || this is DownloadingCheckpoint || this is Verifying
}
