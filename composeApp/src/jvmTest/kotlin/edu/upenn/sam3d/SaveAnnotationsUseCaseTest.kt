package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.AnnotationFile
import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.domain.model.SliceAnnotation
import edu.upenn.sam3d.domain.usecase.SaveAnnotationsUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4 / STEP 1 — written before SaveAnnotationsUseCase exists (TDD).
 *
 * Ground truth is composeApp/src/jvmTest/resources/fixtures/points.fixture.json, produced by a real
 * run of reprompting3d.py. Decoding that fixture shows three positive polylines:
 *   • polyline 0 — every point has z=0   → drawn on AXIS_2, slice 0
 *   • polyline 1 — every point has z=100 → drawn on AXIS_2, slice 100
 *   • polyline 2 — every point has x=200 → drawn on AXIS_0, slice 200
 * so the fixture exercises BOTH axis conventions (§8.3): slice index at [2] for AXIS_2 and at [0]
 * for AXIS_0. We reconstruct the equivalent List<SliceAnnotation> here and assert the use case
 * reproduces the fixture.
 */
class SaveAnnotationsUseCaseTest {

    private val json = Json { prettyPrint = true }

    /** The hand-built annotations that must serialise to points.fixture.json. */
    private fun drawnAnnotations(): List<SliceAnnotation> = listOf(
        SliceAnnotation(
            axis = Axis.AXIS_2, sliceIndex = 0,
            positivePolylines = listOf(
                listOf(
                    intArrayOf(252, 242, 0),
                    intArrayOf(199, 294, 0),
                    intArrayOf(250, 327, 0),
                    intArrayOf(267, 273, 0),
                )
            ),
            negativePolylines = emptyList(),
        ),
        SliceAnnotation(
            axis = Axis.AXIS_2, sliceIndex = 100,
            positivePolylines = listOf(
                listOf(
                    intArrayOf(227, 252, 100),
                    intArrayOf(174, 290, 100),
                    intArrayOf(221, 334, 100),
                    intArrayOf(240, 287, 100),
                )
            ),
            negativePolylines = emptyList(),
        ),
        SliceAnnotation(
            axis = Axis.AXIS_0, sliceIndex = 200,
            positivePolylines = listOf(
                listOf(
                    intArrayOf(200, 333, 129),
                    intArrayOf(200, 318, 281),
                    intArrayOf(200, 278, 180),
                    intArrayOf(200, 302, 199),
                )
            ),
            negativePolylines = emptyList(),
        ),
    )

    private fun fixtureText(): String =
        SaveAnnotationsUseCaseTest::class.java.getResourceAsStream("/fixtures/points.fixture.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("points.fixture.json missing from test resources")

    private fun fixtureAnnotationFile(): AnnotationFile = json.decodeFromString(fixtureText())

    @Test
    fun `output uses top-level keys positive and negative, not pos and neg`() {
        val out = SaveAnnotationsUseCase().toJson(drawnAnnotations())
        assertTrue("\"positive\"" in out, "must use the key \"positive\"")
        assertTrue("\"negative\"" in out, "must use the key \"negative\"")
        assertFalse("\"pos\"" in out, "must NOT use the abbreviated key \"pos\"")
        assertFalse("\"neg\"" in out, "must NOT use the abbreviated key \"neg\"")
    }

    @Test
    fun `every coordinate value is an integer`() {
        val out = SaveAnnotationsUseCase().toJson(drawnAnnotations())
        // No floating-point literals anywhere in the document.
        val floatLiteral = Regex("""-?\d+\.\d+""")
        assertFalse(floatLiteral.containsMatchIn(out), "found a non-integer coordinate in: $out")
        // And it decodes cleanly into the integer-typed model (would throw on a float literal).
        val decoded = json.decodeFromString<AnnotationFile>(out)
        decoded.positive.flatten().forEach { point ->
            assertEquals(3, point.size, "each point must be [x, y, z]")
        }
    }

    @Test
    fun `serialised output reproduces the reprompting3d fixture exactly`() {
        val out = SaveAnnotationsUseCase().toJson(drawnAnnotations())
        // Whitespace-independent structural comparison against the ground-truth fixture.
        assertEquals(fixtureAnnotationFile(), json.decodeFromString<AnnotationFile>(out))
    }

    @Test
    fun `on AXIS_2 annotations the slice index is at point position 2`() {
        val produced = SaveAnnotationsUseCase().toAnnotationFile(drawnAnnotations())
        // drawnAnnotations[0] = AXIS_2 slice 0  → produced.positive[0]
        produced.positive[0].forEach { p -> assertEquals(0, p[2], "AXIS_2 slice index belongs at [2]") }
        // drawnAnnotations[1] = AXIS_2 slice 100 → produced.positive[1]
        produced.positive[1].forEach { p -> assertEquals(100, p[2], "AXIS_2 slice index belongs at [2]") }
    }

    @Test
    fun `on AXIS_0 annotations the slice index is at point position 0`() {
        val produced = SaveAnnotationsUseCase().toAnnotationFile(drawnAnnotations())
        // drawnAnnotations[2] = AXIS_0 slice 200 → produced.positive[2]
        produced.positive[2].forEach { p -> assertEquals(200, p[0], "AXIS_0 slice index belongs at [0]") }
    }

    @Test
    fun `empty polylines are dropped, matching reprompting3d save_points`() {
        val withEmpties = listOf(
            SliceAnnotation(
                axis = Axis.AXIS_2, sliceIndex = 5,
                positivePolylines = listOf(emptyList(), listOf(intArrayOf(1, 2, 5))),
                negativePolylines = listOf(emptyList()),
            )
        )
        val produced = SaveAnnotationsUseCase().toAnnotationFile(withEmpties)
        assertEquals(1, produced.positive.size, "empty positive polyline must be filtered out")
        assertTrue(produced.negative.isEmpty(), "empty negative polyline must be filtered out")
    }

    @Test
    fun `save deletes any previous tempdir and writes points json`() = runBlocking {
        val sam3dGcodeDir = Files.createTempDirectory("sam3d-save-test").toFile()
        try {
            // A stale tempdir with leftover content from a prior run.
            val staleTempdir = File(sam3dGcodeDir, "tempdir").apply { mkdirs() }
            val staleFile = File(staleTempdir, "slice_00.png").apply { writeText("stale") }
            assertTrue(staleFile.exists())

            val path = SaveAnnotationsUseCase().save(drawnAnnotations(), sam3dGcodeDir.absolutePath)

            val pointsFile = File(sam3dGcodeDir, "tempdir/points.json")
            assertTrue(pointsFile.exists(), "tempdir/points.json must be written")
            assertEquals(pointsFile.absolutePath, path, "save() returns the written path")
            assertFalse(staleFile.exists(), "the previous tempdir must be deleted and recreated")
            assertEquals(
                fixtureAnnotationFile(),
                json.decodeFromString<AnnotationFile>(pointsFile.readText()),
            )
        } finally {
            sam3dGcodeDir.deleteRecursively()
        }
    }
}
