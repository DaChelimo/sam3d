package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.PipelineProgress
import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.domain.model.WizardStep
import edu.upenn.sam3d.domain.usecase.PipelineRunner
import edu.upenn.sam3d.state.PipelineError
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5 / STEP 7-8 — the ViewModel ↔ PipelineRunner wiring, exercised with a fake runner so it's
 * deterministic (no real 21-minute sam3d.py run). Unconfined scope makes the progress collector and
 * the RunPipeline launch resolve synchronously.
 */
class WizardPipelineWiringTest {

    private class FakeRunner : PipelineRunner {
        val flow = MutableStateFlow<PipelineProgress?>(null)
        override val progress: StateFlow<PipelineProgress?> = flow
        var startedWith: List<String>? = null
        var startedSlices: Int? = null
        var cancelled = false
        override fun start(sam3dGcodeDir: String, dicomPath: String, outputDir: String, pythonExe: String, slices: Int) {
            startedWith = listOf(sam3dGcodeDir, dicomPath, outputDir, pythonExe)
            startedSlices = slices
        }
        override fun cancel() { cancelled = true }
        override fun recentOutput() = "line 18\nline 19\nline 20"
    }

    private fun vm(runner: FakeRunner) =
        WizardViewModel(pipelineRunner = runner, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `COMPLETE auto-advances to DONE carrying the output path`() {
        val runner = FakeRunner()
        val model = vm(runner)
        runner.flow.value = PipelineProgress(PipelineStage.COMPLETE, outputPath = "/out/output.gcode")
        assertEquals(WizardStep.DONE, model.state.value.currentStep)
        assertEquals("/out/output.gcode", model.state.value.outputGcodePath)
    }

    @Test
    fun `ERROR raises a Server error carrying the recent log lines`() {
        val runner = FakeRunner()
        val model = vm(runner)
        runner.flow.value = PipelineProgress(PipelineStage.ERROR)
        val error = model.state.value.error
        assertTrue(error is PipelineError.Server)
        assertEquals("line 18\nline 19\nline 20", (error as PipelineError.Server).body)
    }

    @Test
    fun `progress updates are reflected in state`() {
        val runner = FakeRunner()
        val model = vm(runner)
        runner.flow.value = PipelineProgress(PipelineStage.RUNNING_INFERENCE, stagePercentage = 0.5f)
        assertEquals(PipelineStage.RUNNING_INFERENCE, model.state.value.pipelineProgress?.stage)
        assertEquals(0.5f, model.state.value.pipelineProgress?.stagePercentage)
    }

    @Test
    fun `RunPipeline starts the runner with the configured paths`() {
        val runner = FakeRunner()
        val model = vm(runner)
        model.handle(WizardIntent.SetSam3dGcodeDir("/sam3d"))
        model.handle(WizardIntent.SetDicomFolder("/dicom"))
        model.handle(WizardIntent.SetOutputFolder("/out"))
        model.handle(WizardIntent.SetPythonPath("/env/bin/python"))
        model.handle(WizardIntent.SetSlices(8))
        model.handle(WizardIntent.RunPipeline)
        assertEquals(listOf("/sam3d", "/dicom", "/out", "/env/bin/python"), runner.startedWith)
        assertEquals(8, runner.startedSlices, "the chosen quality (slice count) must reach the runner")
        assertEquals(WizardStep.PROCESSING, model.state.value.currentStep)
    }

    @Test
    fun `CancelPipeline kills the runner and resets to START`() {
        val runner = FakeRunner()
        val model = vm(runner)
        model.handle(WizardIntent.SetSam3dGcodeDir("/sam3d"))
        model.handle(WizardIntent.SetDicomFolder("/dicom"))
        model.handle(WizardIntent.SetOutputFolder("/out"))
        model.handle(WizardIntent.RunPipeline)
        model.handle(WizardIntent.CancelPipeline)
        assertTrue(runner.cancelled)
        assertEquals(WizardStep.START, model.state.value.currentStep)
    }
}
