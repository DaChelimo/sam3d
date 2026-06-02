package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.process.StdoutProgressParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 5 / STEP 2 — every line below is a REAL line from a sam3d.py run
 * (see composeApp/src/jvmTest/resources/fixtures/dry_run_annotated.log).
 */
class StdoutProgressParserTest {

    private fun parser() = StdoutProgressParser()

    @Test
    fun `transforms made maps to LOADING_DICOM`() {
        assertEquals(PipelineStage.LOADING_DICOM, parser().parseLine("transforms made")?.stage)
    }

    @Test
    fun `image loaded maps to PREPARING_SLICES`() {
        assertEquals(PipelineStage.PREPARING_SLICES, parser().parseLine("image loaded")?.stage)
    }

    @Test
    fun `iterator-style Making prompt slices is PREPARING_SLICES and indeterminate`() {
        val r = parser().parseLine("Making prompt slices: 6it [07:04, 70.79s/it]")
        assertEquals(PipelineStage.PREPARING_SLICES, r?.stage)
        assertEquals(0f, r?.stagePercentage, "iterator tqdm has no total → indeterminate")
    }

    @Test
    fun `model loaded maps to RUNNING_INFERENCE`() {
        assertEquals(PipelineStage.RUNNING_INFERENCE, parser().parseLine("model loaded")?.stage)
    }

    @Test
    fun `bare inference tqdm is attributed to the current stage (stateful)`() {
        val p = parser()
        assertEquals(PipelineStage.RUNNING_INFERENCE, p.parseLine("model loaded")?.stage)
        // A bare tqdm line with no stage word — must inherit RUNNING_INFERENCE.
        val r = p.parseLine(" 33%|███▎      | 2/6 [01:10<02:16, 34.19s/it]")
        assertEquals(PipelineStage.RUNNING_INFERENCE, r?.stage)
        assertEquals(2f / 6f, r?.stagePercentage)
    }

    @Test
    fun `number of points maps to BUILDING_POINT_CLOUD`() {
        assertEquals(PipelineStage.BUILDING_POINT_CLOUD, parser().parseLine("number of points:  871503")?.stage)
    }

    @Test
    fun `point cloud refinement maps to BUILDING_POINT_CLOUD`() {
        assertEquals(PipelineStage.BUILDING_POINT_CLOUD, parser().parseLine("point cloud refinement")?.stage)
    }

    @Test
    fun `Executing Voxels2GCode maps to GENERATING_GCODE`() {
        assertEquals(PipelineStage.GENERATING_GCODE, parser().parseLine("Executing Voxels2GCode")?.stage)
    }

    @Test
    fun `Writing G-code tqdm is attributed to GENERATING_GCODE (stateful)`() {
        val p = parser()
        p.parseLine("Executing Voxels2GCode")
        val r = p.parseLine("Writing G-code:  37%|███▋      | 3161/8430 [00:02<00:05, 926.79it/s]")
        assertEquals(PipelineStage.GENERATING_GCODE, r?.stage)
        assertEquals(3161f / 8430f, r?.stagePercentage)
    }

    @Test
    fun `detail surfaces the current gcode tqdm activity`() {
        val p = parser()
        p.parseLine("Executing Voxels2GCode")
        val r = p.parseLine("Extracting paths:  51%|█████     | 4314/8430 [00:38<00:42, 96.87it/s]")
        assertEquals(PipelineStage.GENERATING_GCODE, r?.stage)
        assertEquals("Extracting paths: 4314 / 8430", r?.detail)
    }

    @Test
    fun `detail for a bare inference tqdm falls back to the stage label`() {
        val p = parser()
        p.parseLine("model loaded")
        val r = p.parseLine(" 33%|███▎      | 2/6 [01:10<02:16, 34.19s/it]")
        assertEquals("Running SAM inference: 2 / 6", r?.detail)
    }

    @Test
    fun `iterator-style reslice tqdm reports its step in detail`() {
        val r = parser().parseLine("Making prompt slices: 6it [07:04, 70.79s/it]")
        assertEquals("Making prompt slices: step 6", r?.detail)
    }

    @Test
    fun `GCODE generated maps to COMPLETE`() {
        assertEquals(PipelineStage.COMPLETE, parser().parseLine("GCODE generated")?.stage)
    }

    @Test
    fun `the interactive done prompt is NOT treated as progress or error`() {
        assertNull(parser().parseLine("Enter a command (evaluate, downsample, outliers, done): "))
    }

    @Test
    fun `unrecognised line returns null`() {
        assertNull(parser().parseLine("[ WARN:0@40.148] global loadsave.cpp:1089 imwrite_ fallback"))
    }

    @Test
    fun `empty line returns null`() {
        assertNull(parser().parseLine(""))
    }
}
