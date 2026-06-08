package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.domain.model.formatDuration
import edu.upenn.sam3d.domain.usecase.StageTimer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure per-stage timing logic that feeds the Reports tab. Times are passed in (the class has no
 * clock of its own), so the test is exact and deterministic.
 */
class StageTimerTest {

    @Test
    fun `accumulates per-stage durations in order and a matching total`() {
        val t = StageTimer(startMs = 0)
        t.observe(PipelineStage.LOADING_DICOM, 0)
        t.observe(PipelineStage.PREPARING_SLICES, 5_000)        // LOADING_DICOM took 5s
        t.observe(PipelineStage.RUNNING_INFERENCE, 15_000)      // PREPARING_SLICES took 10s
        t.observe(PipelineStage.RUNNING_INFERENCE, 16_000)      // same stage → no new row
        t.observe(PipelineStage.BUILDING_POINT_CLOUD, 800_000)  // RUNNING_INFERENCE took 785s
        t.observe(PipelineStage.GENERATING_GCODE, 810_000)      // BUILDING_POINT_CLOUD took 10s
        val stages = t.finish(900_000)                          // GENERATING_GCODE took 90s

        assertEquals(
            listOf("LOADING_DICOM", "PREPARING_SLICES", "RUNNING_INFERENCE", "BUILDING_POINT_CLOUD", "GENERATING_GCODE"),
            stages.map { it.stage },
            "stages are recorded once each, in first-seen order",
        )
        assertEquals(listOf(5L, 10L, 785L, 10L, 90L), stages.map { it.seconds })
        assertEquals("Running SAM inference", stages[2].label)
        assertEquals(900L, t.totalSeconds(900_000))
        assertEquals(900L, stages.sumOf { it.seconds }, "per-stage durations sum to the total")
    }

    @Test
    fun `time before the first marker folds into the first stage`() {
        val t = StageTimer(startMs = 1_000)               // process launched at t=1s
        t.observe(PipelineStage.LOADING_DICOM, 4_000)     // first marker only at t=4s (3s of startup)
        t.observe(PipelineStage.RUNNING_INFERENCE, 11_000)
        val stages = t.finish(21_000)

        assertEquals(10L, stages.first { it.stage == "LOADING_DICOM" }.seconds, "startup time is absorbed by stage 1")
        assertEquals(20L, t.totalSeconds(21_000))
    }

    @Test
    fun `terminal stages are not timed buckets`() {
        val t = StageTimer(startMs = 0)
        t.observe(PipelineStage.LOADING_DICOM, 0)
        t.observe(PipelineStage.COMPLETE, 5_000)          // ignored — not a work stage
        val stages = t.finish(5_000)
        assertEquals(listOf("LOADING_DICOM"), stages.map { it.stage })
    }

    @Test
    fun `formatDuration is compact and human`() {
        assertEquals("0s", formatDuration(0))
        assertEquals("47s", formatDuration(47))
        assertEquals("1m 00s", formatDuration(60))
        assertEquals("13m 22s", formatDuration(802))
        assertEquals("20m 56s", formatDuration(20 * 60 + 56))
        assertEquals("1h 00m", formatDuration(3600))
        assertEquals("1h 01m", formatDuration(3660))
    }
}
