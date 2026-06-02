package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.domain.model.SliceAnnotation
import edu.upenn.sam3d.domain.model.WizardStep
import edu.upenn.sam3d.domain.usecase.AnnotationSaver
import edu.upenn.sam3d.state.DrawingMode
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 4 — covers the ViewModel annotation-accumulation path that the Prompting canvas drives
 * (AddPolylinePoint / EndPolyline / DeleteLastPoint / ClearSlice) and the RunPipeline → points.json
 * write. WizardViewModel updates its StateFlow synchronously, so we assert on state.value directly.
 */
class WizardAnnotationTest {

    @Test
    fun `AddPolylinePoint on AXIS_2 embeds the slice index at position 2`() {
        val vm = WizardViewModel()
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, sliceIndex = 45, x = 10, y = 20, mode = DrawingMode.POSITIVE))
        val point = vm.state.value.annotations.single().positivePolylines.single().single()
        assertContentEquals(intArrayOf(10, 20, 45), point)
    }

    @Test
    fun `AddPolylinePoint on AXIS_0 embeds the slice index at position 0`() {
        val vm = WizardViewModel()
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_0, sliceIndex = 7, x = 10, y = 20, mode = DrawingMode.POSITIVE))
        val point = vm.state.value.annotations.single().positivePolylines.single().single()
        assertContentEquals(intArrayOf(7, 10, 20), point)
    }

    @Test
    fun `consecutive points extend one polyline and EndPolyline starts a new one`() {
        val vm = WizardViewModel()
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 45, 1, 1, DrawingMode.POSITIVE))
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 45, 2, 2, DrawingMode.POSITIVE))
        assertEquals(1, vm.state.value.annotations.single().positivePolylines.size)
        assertEquals(2, vm.state.value.annotations.single().positivePolylines.single().size)

        vm.handle(WizardIntent.EndPolyline)
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 45, 3, 3, DrawingMode.POSITIVE))
        assertEquals(2, vm.state.value.annotations.single().positivePolylines.size)
    }

    @Test
    fun `changing slice keeps annotations in separate per-slice buckets`() {
        val vm = WizardViewModel()
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 45, 1, 1, DrawingMode.POSITIVE))
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 46, 2, 2, DrawingMode.POSITIVE))
        val anns = vm.state.value.annotations
        assertEquals(2, anns.size)
        assertEquals(setOf(45, 46), anns.map { it.sliceIndex }.toSet())
    }

    @Test
    fun `negative points go to negativePolylines`() {
        val vm = WizardViewModel()
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 45, 1, 1, DrawingMode.NEGATIVE))
        val ann = vm.state.value.annotations.single()
        assertTrue(ann.positivePolylines.all { it.isEmpty() })
        assertEquals(1, ann.negativePolylines.single().size)
    }

    @Test
    fun `DeleteLastPoint removes the last vertex and drops an emptied slice`() {
        val vm = WizardViewModel()
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 45, 1, 1, DrawingMode.POSITIVE))
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 45, 2, 2, DrawingMode.POSITIVE))
        vm.handle(WizardIntent.DeleteLastPoint(Axis.AXIS_2, 45, DrawingMode.POSITIVE))
        assertContentEquals(
            intArrayOf(1, 1, 45),
            vm.state.value.annotations.single().positivePolylines.single().single(),
        )
        vm.handle(WizardIntent.DeleteLastPoint(Axis.AXIS_2, 45, DrawingMode.POSITIVE))
        assertTrue(vm.state.value.annotations.isEmpty(), "emptied slice annotation is dropped")
    }

    @Test
    fun `ClearSlice removes only the targeted slice`() {
        val vm = WizardViewModel()
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 45, 1, 1, DrawingMode.POSITIVE))
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 46, 2, 2, DrawingMode.POSITIVE))
        vm.handle(WizardIntent.ClearSlice(Axis.AXIS_2, 45))
        assertEquals(46, vm.state.value.annotations.single().sliceIndex)
    }

    @Test
    fun `RunPipeline writes points json via the saver and advances to PROCESSING`() {
        val saver = RecordingSaver()
        val vm = WizardViewModel(annotationSaver = saver, scope = CoroutineScope(Dispatchers.Unconfined))
        vm.handle(WizardIntent.SetSam3dGcodeDir("/fake/sam3d"))
        vm.handle(WizardIntent.AddPolylinePoint(Axis.AXIS_2, 45, 1, 1, DrawingMode.POSITIVE))
        vm.handle(WizardIntent.RunPipeline)
        assertEquals("/fake/sam3d", saver.dir, "save() must run with the configured SAM3D-GCODE dir")
        assertEquals(1, saver.saved?.size, "the drawn annotations must be passed to save()")
        assertEquals(WizardStep.PROCESSING, vm.state.value.currentStep)
    }

    private class RecordingSaver : AnnotationSaver {
        var saved: List<SliceAnnotation>? = null
        var dir: String? = null
        override suspend fun save(annotations: List<SliceAnnotation>, sam3dGcodeDir: String): String {
            saved = annotations
            dir = sam3dGcodeDir
            return "$sam3dGcodeDir/tempdir/points.json"
        }
    }
}
