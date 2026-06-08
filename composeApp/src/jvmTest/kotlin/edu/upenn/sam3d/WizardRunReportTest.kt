package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.AppView
import edu.upenn.sam3d.domain.model.PipelineProgress
import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.domain.model.QualityPreset
import edu.upenn.sam3d.domain.model.RunReport
import edu.upenn.sam3d.domain.model.RunStatus
import edu.upenn.sam3d.domain.model.RunTiming
import edu.upenn.sam3d.domain.model.StageDuration
import edu.upenn.sam3d.domain.repository.RunReportRepository
import edu.upenn.sam3d.domain.usecase.PipelineRunner
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The run → RunReport recording path: a terminal pipeline event carrying [RunTiming] should produce a
 * report combining the timing with the run's config (quality, slices, resolution), surface it on the
 * Done screen, and persist it. Fake runner + store keep it deterministic; Unconfined makes the
 * collector and the record coroutine resolve inline.
 */
class WizardRunReportTest {

    private class FakeRunner : PipelineRunner {
        val flow = MutableStateFlow<PipelineProgress?>(null)
        override val progress: StateFlow<PipelineProgress?> = flow
        override fun start(sam3dGcodeDir: String, dicomPath: String, outputDir: String, pythonExe: String, slices: Int) {}
        override fun cancel() {}
        override fun recentOutput() = "boom"
    }

    private class FakeStore : RunReportRepository {
        val saved = mutableListOf<RunReport>()
        override suspend fun loadAll(): List<RunReport> = saved.toList()
        override suspend fun append(report: RunReport) {
            saved.removeAll { it.id == report.id }
            saved.add(0, report)
        }
    }

    private fun timing(id: String = "20260607-143501") = RunTiming(
        id = id,
        startedAtEpochMs = 1_700_000_000_000,
        startedAtDisplay = "Jun 7, 2026 at 2:35 PM",
        stages = listOf(
            StageDuration("RUNNING_INFERENCE", "Running SAM inference", 780),
            StageDuration("GENERATING_GCODE", "Generating G-code", 120),
        ),
        totalSeconds = 900,
    )

    private fun vm(runner: FakeRunner, store: FakeStore) = WizardViewModel(
        pipelineRunner = runner,
        reportStore = store,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `COMPLETE with timing records a report with the run config`() {
        val runner = FakeRunner(); val store = FakeStore()
        val model = vm(runner, store)
        model.handle(WizardIntent.SetQuality(QualityPreset.DRAFT))   // quality=Draft, slices=8, target=256

        runner.flow.value = PipelineProgress(PipelineStage.COMPLETE, outputPath = "/out/output.gcode", timing = timing())

        val report = model.state.value.lastRunReport
        assertTrue(report != null, "the finished run is surfaced on the Done screen")
        assertEquals("Draft", report.quality)
        assertEquals(8, report.slices)
        assertEquals(256, report.downsampleTargetMaxDim)
        assertEquals(RunStatus.COMPLETE, report.status)
        assertEquals(900, report.totalSeconds)
        assertEquals("/out/output.gcode", report.outputPath)
        assertEquals(2, report.stages.size)

        assertEquals(1, store.saved.size, "the report is persisted")
        assertEquals(listOf(report.id), model.state.value.reports.map { it.id }, "state.reports is refreshed from the store")
    }

    @Test
    fun `ERROR with timing records a failed report with no output`() {
        val runner = FakeRunner(); val store = FakeStore()
        val model = vm(runner, store)
        model.handle(WizardIntent.SetQuality(QualityPreset.PRODUCTION))

        runner.flow.value = PipelineProgress(PipelineStage.ERROR, timing = timing("err-1"))

        val report = model.state.value.lastRunReport
        assertTrue(report != null)
        assertEquals(RunStatus.ERROR, report.status)
        assertEquals("Production", report.quality)
        assertNull(report.downsampleTargetMaxDim, "Production runs at full resolution")
        assertNull(report.outputPath)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun `a COMPLETE without timing does not record anything`() {
        val runner = FakeRunner(); val store = FakeStore()
        val model = vm(runner, store)
        // The parser can emit COMPLETE from "GCODE generated" before the process layer attaches timing.
        runner.flow.value = PipelineProgress(PipelineStage.COMPLETE, outputPath = "/out/output.gcode")
        assertNull(model.state.value.lastRunReport)
        assertEquals(0, store.saved.size)
    }

    @Test
    fun `opening the Reports tab loads persisted reports`() {
        val runner = FakeRunner(); val store = FakeStore()
        store.saved.add(
            RunReport("seed", 1L, "earlier", "Draft", 8, 256, RunStatus.COMPLETE, emptyList(), 120, null)
        )
        val model = vm(runner, store)

        model.handle(WizardIntent.SetAppView(AppView.REPORTS))

        assertEquals(AppView.REPORTS, model.state.value.appView)
        assertEquals(listOf("seed"), model.state.value.reports.map { it.id })
    }
}
