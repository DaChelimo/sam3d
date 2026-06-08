package edu.upenn.sam3d.domain.model

import kotlinx.serialization.Serializable

/** How a recorded run ended. CANCELLED runs aren't stored (the user aborted them on purpose). */
@Serializable
enum class RunStatus { COMPLETE, ERROR }

/**
 * Measured wall-clock spent in one [PipelineStage], captured by [edu.upenn.sam3d.domain.usecase.StageTimer].
 * [stage] is the enum *name* (a stable key) and [label] the human string captured at run time, so an
 * old report keeps its original wording even if the enum's labels later change. [seconds] is rounded.
 */
@Serializable
data class StageDuration(
    val stage: String,
    val label: String,
    val seconds: Long,
)

/**
 * One pipeline run, persisted to `<userDataDir>/SAM3D/reports.json` for the Reports tab. Records the
 * *configuration* that was used (so toggling Draft/Production, the slice count, or the downsample
 * resolution can be correlated with timing) and the *timing* (per-stage + total). One row per run.
 */
@Serializable
data class RunReport(
    /** Compact stable id derived from the start time, e.g. "20260607-143501". */
    val id: String,
    val startedAtEpochMs: Long,
    /** Pre-formatted local start time for the UI (formatted on the JVM side; commonMain has no clock). */
    val startedAtDisplay: String,
    /** Quality preset label used ("Draft" / "Production"). */
    val quality: String,
    /** sam3d.py `-s` slice count. */
    val slices: Int,
    /** Downsample target (longest cube side, voxels); null = full resolution (Production). */
    val downsampleTargetMaxDim: Int?,
    val status: RunStatus,
    /** Per-stage durations in pipeline order. */
    val stages: List<StageDuration>,
    /** Total wall-clock for the whole run, in seconds. */
    val totalSeconds: Long,
    val outputPath: String? = null,
) {
    /** "256³ cube" for a downsampled Draft, or "Full" for Production — for compact config display. */
    val resolutionLabel: String get() = downsampleTargetMaxDim?.let { "$it³" } ?: "Full"
}

/**
 * The timing half of a finished run, produced by the JVM process layer and carried on the terminal
 * [PipelineProgress.timing]. The ViewModel combines it with the config snapshot in WizardState to
 * build a [RunReport]. Kept separate from RunReport (and not serialized) because the process layer
 * knows the timing but not the user's quality/slice choices.
 */
data class RunTiming(
    val id: String,
    val startedAtEpochMs: Long,
    val startedAtDisplay: String,
    val stages: List<StageDuration>,
    val totalSeconds: Long,
)

/** "1h 04m" / "13m 22s" / "47s" — compact, human duration used across the Done screen and Reports. */
fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
        m > 0 -> "${m}m ${sec.toString().padStart(2, '0')}s"
        else -> "${sec}s"
    }
}
