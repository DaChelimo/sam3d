package edu.upenn.sam3d.domain.model

data class PipelineProgress(
    val stage: PipelineStage,
    val stagePercentage: Float = 0f,
    val elapsedSeconds: Long = 0L,
    val outputPath: String? = null,
    // Human-readable "what's running right now" line built from stdout (e.g. "Extracting paths: 4314 / 8430").
    val detail: String? = null,
    // Seconds remaining in the CURRENT stage, parsed from tqdm's own "[mm:ss<mm:ss]" estimate when
    // present (null otherwise). Used by the UI to show an ETA without guessing.
    val etaSeconds: Long? = null,
    // Final per-stage + total timing, attached only to the terminal COMPLETE/ERROR emission by the
    // process layer. Null on every in-flight tick. The ViewModel turns it into a persisted RunReport.
    val timing: RunTiming? = null,
    // The subprocess's real exit code, attached only to the terminal ERROR emission. On Unix a
    // signal-terminated process reports 128+signal — notably 137 (128+SIGKILL), the reliable signature
    // of the OS itself killing the process (almost always memory pressure), as opposed to a normal
    // Python exception exit (small positive code). Used by FailureHints to give an accurate diagnosis.
    val exitCode: Int? = null,
)
