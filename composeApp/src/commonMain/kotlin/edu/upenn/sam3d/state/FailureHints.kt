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

    fun classify(output: String): String? =
        rules.firstOrNull { (re, _) -> re.containsMatchIn(output) }?.second
}
