package edu.upenn.sam3d.domain.usecase

import edu.upenn.sam3d.domain.model.PipelineProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * Port (commonMain) the WizardViewModel uses to drive the sam3d.py subprocess, implemented in
 * jvmMain (PythonPipelineRunner → PythonProcessManager). Keeps ProcessBuilder/java.nio out of
 * commonMain (§2 non-negotiable #3) while letting the ViewModel start/cancel runs and observe
 * progress.
 */
interface PipelineRunner {
    /** Latest pipeline progress; null before a run / after cancel. */
    val progress: StateFlow<PipelineProgress?>

    /** Spawn sam3d.py with the working dir = [sam3dGcodeDir] (§7.1). Paths are absolute strings. */
    fun start(sam3dGcodeDir: String, dicomPath: String, outputDir: String, pythonExe: String)

    /** Force-kill the subprocess (Process.destroyForcibly) and clear progress. */
    fun cancel()

    /** Last ~20 lines of subprocess output, for the error dialog when a run fails. */
    fun recentOutput(): String
}
