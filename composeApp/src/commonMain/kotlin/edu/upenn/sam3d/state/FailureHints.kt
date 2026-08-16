package edu.upenn.sam3d.state

/**
 * Turns the tail of a failed sam3d.py run into a short, plain-English diagnosis for the error dialog
 * (Phase 6 task 4 — "optionally tailor the message by failure type"). Pure and commonMain so it can
 * be unit-tested without a real subprocess. Returns null when nothing recognisable is found, in which
 * case the dialog just shows the raw output.
 */
object FailureHints {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("CUDA out of memory|OutOfMemoryError|RuntimeError: .*memory", RegexOption.IGNORE_CASE)
            to "The machine ran out of GPU/CPU memory. Try a smaller volume or close other apps.",
        Regex("No such file or directory.*checkpoint|sam_vit_h_4b8939\\.pth|checkpoint.*not found", RegexOption.IGNORE_CASE)
            to "The SAM checkpoint couldn't be found. Re-check the SAM3D-GCODE directory and download the checkpoint on the Start screen.",
        Regex("ModuleNotFoundError|No module named", RegexOption.IGNORE_CASE)
            to "A Python dependency is missing. Make sure the Python binary points at the sam3d conda environment.",
        Regex("No new output for|appears stuck|run limit|run was stopped|Timed out", RegexOption.IGNORE_CASE)
            to "The run was stopped after it stopped making progress. It may be stuck — check the log, and for long runs keep the machine awake / on power.",
        Regex("FileNotFoundError|No \\.dcm files|No valid DICOM", RegexOption.IGNORE_CASE)
            to "The DICOM input couldn't be read. Confirm the DICOM folder contains the series.",
        Regex("Permission denied", RegexOption.IGNORE_CASE)
            to "Permission was denied. On macOS you may need to allow the Python binary in System Settings → Privacy & Security.",
    )

    /** 128 + SIGKILL — how the JVM reports a process that was terminated by a signal (POSIX convention;
     *  see [ProcessBuilder]/[Process.waitFor] on Unix). We only ever send SIGKILL ourselves via
     *  `destroyForcibly()` on cancel or our own inactivity/max-run timeout — both cases are already
     *  caught by the [rules] above (their text lands in [output] first). So by the time this constant
     *  is checked, a 137 reliably means something OUTSIDE the app killed the process — almost always
     *  the OS terminating it for running out of memory, the classic failure mode for a full-resolution
     *  Production run. */
    private const val SIGKILL_EXIT_CODE = 137

    /**
     * [exitCode] is the subprocess's real exit status when available (see PipelineProgress.exitCode).
     * Text rules are checked first since they're more specific (e.g. our own "run was stopped" note
     * also exits via SIGKILL/137, but should keep its own precise message); the exit-code check is a
     * fallback for deaths with no recognisable text at all — e.g. the OS silently killing the process.
     */
    fun classify(output: String, exitCode: Int? = null): String? {
        rules.firstOrNull { (re, _) -> re.containsMatchIn(output) }?.let { return it.second }
        if (exitCode == SIGKILL_EXIT_CODE) {
            return "The pipeline process was terminated by the operating system — this almost always " +
                "means it ran out of memory (common for Production/full-resolution runs on machines " +
                "with less RAM). Try the Draft preset, close other apps, or run on a machine with more RAM."
        }
        return null
    }
}
