package edu.upenn.sam3d

import app.cash.turbine.test
import edu.upenn.sam3d.domain.model.WizardStep
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardViewModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WizardViewModelTest {

    @Test
    fun `initial state is WizardStep START`() {
        val vm = WizardViewModel()
        assertEquals(WizardStep.START, vm.state.value.currentStep)
    }

    @Test
    fun `RunPipeline intent transitions state to PROCESSING`() = runTest {
        val vm = WizardViewModel()
        vm.state.test {
            assertEquals(WizardStep.START, awaitItem().currentStep)
            vm.handle(WizardIntent.RunPipeline)
            assertEquals(WizardStep.PROCESSING, awaitItem().currentStep)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CancelPipeline from PROCESSING returns to START`() = runTest {
        val vm = WizardViewModel()
        vm.handle(WizardIntent.RunPipeline)
        vm.state.test {
            assertEquals(WizardStep.PROCESSING, awaitItem().currentStep)
            vm.handle(WizardIntent.CancelPipeline)
            assertEquals(WizardStep.START, awaitItem().currentStep)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `StartOver from DONE returns to START`() = runTest {
        val vm = WizardViewModel()
        vm.handle(WizardIntent.RunPipeline)
        vm.handle(WizardIntent.PipelineComplete(outputPath = "/tmp/output.gcode"))
        vm.state.test {
            assertEquals(WizardStep.DONE, awaitItem().currentStep)
            vm.handle(WizardIntent.StartOver)
            assertEquals(WizardStep.START, awaitItem().currentStep)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
