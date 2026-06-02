package edu.upenn.sam3d

import androidx.compose.ui.geometry.Rect
import edu.upenn.sam3d.domain.model.AnnotationFile
import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.domain.model.SliceAnnotation
import edu.upenn.sam3d.domain.usecase.SaveAnnotationsUseCase
import edu.upenn.sam3d.ui.canvas.displayToVoxel
import edu.upenn.sam3d.ui.canvas.letterboxRect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4 / STEP 7 (headless equivalent). Drives the real production save path — clicks turned into
 * voxels by [displayToVoxel], accumulated into per-slice [SliceAnnotation]s, written by the real
 * [SaveAnnotationsUseCase] — for THREE annotated slices (two on AXIS_2, one on AXIS_0), then checks
 * the on-disk points.json against the STEP 7 acceptance criteria. The file is left under
 * composeApp/build/phase4-validation/tempdir/points.json for manual inspection.
 *
 * The interactive GUI walk-through (drawing with the mouse, then clicking "Run Pipeline") still has
 * to be done by a human — this proves the bytes that path produces are correct.
 */
class PromptingEndToEndValidationTest {

    // §8.4 worked example geometry: 800×600 canvas, S=512 cube.
    private val s = 512
    private val rect: Rect = letterboxRect(800f, 600f, s)

    @Test
    fun `three annotated slices produce a valid points json`() = runBlocking {
        // Slice 45 on AXIS_2 — two clicks (the first is the §8.4 example → [128,153,45]).
        val axis2slice45 = SliceAnnotation(
            axis = Axis.AXIS_2, sliceIndex = 45,
            positivePolylines = listOf(
                listOf(
                    displayToVoxel(250f, 180f, rect, s, Axis.AXIS_2, 45),
                    displayToVoxel(330f, 250f, rect, s, Axis.AXIS_2, 45),
                )
            ),
            negativePolylines = emptyList(),
        )
        // Slice 120 on AXIS_2 — two clicks.
        val axis2slice120 = SliceAnnotation(
            axis = Axis.AXIS_2, sliceIndex = 120,
            positivePolylines = listOf(
                listOf(
                    displayToVoxel(280f, 200f, rect, s, Axis.AXIS_2, 120),
                    displayToVoxel(360f, 300f, rect, s, Axis.AXIS_2, 120),
                )
            ),
            negativePolylines = emptyList(),
        )
        // Slice 30 on AXIS_0 — two clicks (slice index lands at [0]).
        val axis0slice30 = SliceAnnotation(
            axis = Axis.AXIS_0, sliceIndex = 30,
            positivePolylines = listOf(
                listOf(
                    displayToVoxel(250f, 180f, rect, s, Axis.AXIS_0, 30),
                    displayToVoxel(330f, 250f, rect, s, Axis.AXIS_0, 30),
                )
            ),
            negativePolylines = emptyList(),
        )
        val annotations = listOf(axis2slice45, axis2slice120, axis0slice30)

        val sam3dGcodeDir = File("build/phase4-validation").absoluteFile.apply { mkdirs() }
        val written = SaveAnnotationsUseCase().save(annotations, sam3dGcodeDir.path)

        val pointsFile = File(sam3dGcodeDir, "tempdir/points.json")
        assertTrue(pointsFile.exists(), "tempdir/points.json must be written")
        assertEquals(pointsFile.absolutePath, written)

        val text = pointsFile.readText()
        // (1) keys are "positive"/"negative"
        assertTrue("\"positive\"" in text && "\"negative\"" in text)
        assertFalse("\"pos\"" in text || "\"neg\"" in text)
        // (2) all coordinate values are integers
        assertFalse(Regex("""-?\d+\.\d+""").containsMatchIn(text), "non-integer coordinate found")

        val decoded = Json.decodeFromString<AnnotationFile>(text)
        // (3) on AXIS_2 annotations, the slice index is at position [2]
        decoded.positive[0].forEach { assertEquals(45, it[2]) }
        decoded.positive[1].forEach { assertEquals(120, it[2]) }
        // and on the AXIS_0 annotation, the slice index is at position [0]
        decoded.positive[2].forEach { assertEquals(30, it[0]) }
    }
}
