package edu.upenn.sam3d.domain.usecase

import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.domain.model.StageDuration

/**
 * Accumulates measured wall-clock per [PipelineStage] from a stream of stage observations. Pure
 * (commonMain, no clock of its own) — the caller passes the current epoch-millis with each event, so
 * the timing is real and the class stays unit-testable.
 *
 * Model: time spent *before* the first work-stage marker (Python startup, imports, the initial load)
 * is folded into that first stage, and time from the last marker to [finish] is folded into the last
 * stage — so the per-stage durations sum to ~the total and nothing is dropped. Re-observing the same
 * stage (it emits several marker lines) or a stage already seen just keeps accruing to it, so the
 * output stays one row per stage in first-seen order.
 *
 * @param startMs the run's start time (process launch), in epoch millis.
 */
class StageTimer(private val startMs: Long) {
    // First-seen insertion order; values are accumulated millis. LinkedHashMap keeps the order stable.
    private val durations = LinkedHashMap<PipelineStage, Long>()
    private var current: PipelineStage? = null
    private var stageStartMs = startMs

    /** Feed a stage seen at [nowMs]. Non-work stages (COMPLETE/ERROR) are ignored — call [finish]. */
    fun observe(stage: PipelineStage, nowMs: Long) {
        if (stage !in WORK_STAGES) return
        val cur = current
        if (cur == null) {
            // First work stage: its clock starts at the run start (absorbing pre-marker startup time).
            current = stage
            stageStartMs = startMs
            return
        }
        if (stage != cur) {
            durations[cur] = (durations[cur] ?: 0L) + (nowMs - stageStartMs).coerceAtLeast(0)
            current = stage
            stageStartMs = nowMs
        }
    }

    /** Close the open stage at [nowMs] and return the per-stage durations (rounded to seconds). */
    fun finish(nowMs: Long): List<StageDuration> {
        current?.let { cur ->
            durations[cur] = (durations[cur] ?: 0L) + (nowMs - stageStartMs).coerceAtLeast(0)
        }
        return durations.map { (stage, ms) -> StageDuration(stage.name, stage.label, roundToSeconds(ms)) }
    }

    /** Total run wall-clock at [nowMs], rounded to seconds. */
    fun totalSeconds(nowMs: Long): Long = roundToSeconds((nowMs - startMs).coerceAtLeast(0))

    private fun roundToSeconds(ms: Long): Long = (ms + 500) / 1000

    companion object {
        /** The five real stages, in order; COMPLETE/ERROR are terminal, not timed buckets. */
        val WORK_STAGES = listOf(
            PipelineStage.LOADING_DICOM,
            PipelineStage.PREPARING_SLICES,
            PipelineStage.RUNNING_INFERENCE,
            PipelineStage.BUILDING_POINT_CLOUD,
            PipelineStage.GENERATING_GCODE,
        )
    }
}
