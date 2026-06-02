package edu.upenn.sam3d

import androidx.compose.ui.geometry.Rect
import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.ui.canvas.displayToVoxel
import edu.upenn.sam3d.ui.canvas.letterboxRect
import edu.upenn.sam3d.ui.canvas.voxelToDisplay
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 / STEP 3 — validates the §8.3 / §8.5 coordinate transforms against the §8.4 worked
 * example: canvas 800×600, S=512, pointer (250, 180).
 */
class CoordinateTransformsTest {

    private val canvasW = 800f
    private val canvasH = 600f
    private val s = 512

    // §8.4: scale = min(800/512, 600/512) = 1.171875 → 600×600 image, offsetX=100, offsetY=0.
    private val rect = letterboxRect(canvasW, canvasH, s)

    @Test
    fun `letterbox rect matches the worked example`() {
        assertEquals(100f, rect.left, "offsetX = (800 - 600) / 2")
        assertEquals(0f, rect.top, "offsetY = (600 - 600) / 2")
        assertEquals(600f, rect.width, "displayW = 512 * 1.171875")
        assertEquals(600f, rect.height, "displayH = 512 * 1.171875")
    }

    @Test
    fun `pointer 250 180 on AXIS_2 slice 45 maps to voxel 128 153 45`() {
        val voxel = displayToVoxel(250f, 180f, rect, s, Axis.AXIS_2, sliceIndex = 45)
        assertContentEquals(intArrayOf(128, 153, 45), voxel)
    }

    @Test
    fun `pointer 250 180 on AXIS_0 slice 45 maps to voxel 45 128 153`() {
        val voxel = displayToVoxel(250f, 180f, rect, s, Axis.AXIS_0, sliceIndex = 45)
        assertContentEquals(intArrayOf(45, 128, 153), voxel)
    }

    @Test
    fun `voxelToDisplay is the inverse of displayToVoxel on the active slice`() {
        // [128,153,45] on AXIS_2 → x = 100 + 128/512*600 = 250 exactly; y = 153/512*600 = 179.296875.
        val offset = voxelToDisplay(intArrayOf(128, 153, 45), Axis.AXIS_2, sliceIndex = 45, rect, s)!!
        assertEquals(250f, offset.x)
        assertTrue(abs(offset.y - 179.296875f) < 0.001f, "got ${offset.y}")
    }

    @Test
    fun `voxelToDisplay returns null for a point not on the shown slice`() {
        // point's z is 45, but we are viewing AXIS_2 slice 46 → not rendered.
        assertNull(voxelToDisplay(intArrayOf(128, 153, 45), Axis.AXIS_2, sliceIndex = 46, rect, s))
    }

    @Test
    fun `displayToVoxel clamps clicks outside the image into the cube`() {
        // Click far to the upper-left of the letterboxed image → clamped to (0, 0).
        val voxel = displayToVoxel(0f, 0f, rect, s, Axis.AXIS_2, sliceIndex = 10)
        assertContentEquals(intArrayOf(0, 0, 10), voxel)
        // Click far to the lower-right → clamped to (S-1, S-1).
        val voxel2 = displayToVoxel(10_000f, 10_000f, rect, s, Axis.AXIS_2, sliceIndex = 10)
        assertContentEquals(intArrayOf(s - 1, s - 1, 10), voxel2)
    }
}
