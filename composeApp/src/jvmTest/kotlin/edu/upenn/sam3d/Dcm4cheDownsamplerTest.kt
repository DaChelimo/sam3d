package edu.upenn.sam3d

import edu.upenn.sam3d.dicom.Dcm4cheDownsampler
import edu.upenn.sam3d.dicom.Dcm4cheLoader
import edu.upenn.sam3d.dicom.boxAverageInPlane
import edu.upenn.sam3d.dicom.computeFactors
import kotlinx.coroutines.runBlocking
import org.dcm4che3.data.Tag
import org.dcm4che3.io.DicomInputStream
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Dcm4cheDownsamplerTest {

    // ── Pure factor math (CI-safe, no data) ──────────────────────────────────

    @Test
    fun `computeFactors - 512x512x562 target 128 picks f=4 k=5`() {
        val f = computeFactors(512, 512, 562, 128)
        assertEquals(4, f.f, "in-plane factor")
        assertEquals(5, f.k, "z stride")
        assertEquals(128, f.newRows)
        assertEquals(128, f.newCols)
        assertEquals(113, f.outSlices, "ceil(562/5)")
    }

    @Test
    fun `computeFactors - already small volume keeps factor 1`() {
        val f = computeFactors(100, 100, 80, 128)
        assertEquals(1, f.f)
        assertEquals(1, f.k)
        assertEquals(100, f.newRows)
        assertEquals(80, f.outSlices)
    }

    @Test
    fun `boxAverageInPlane - 4x4 with f=2 averages each 2x2 block`() {
        val src = IntArray(16) { it }   // 0..15 row-major
        val out = boxAverageInPlane(src, rows = 4, cols = 4, f = 2)
        // block sums /4: (0,1,4,5)=2  (2,3,6,7)=4  (8,9,12,13)=10  (10,11,14,15)=12
        assertContentEquals(intArrayOf(2, 4, 10, 12), out)
    }

    @Test
    fun `scaled spacing preserves physical extent`() {
        // PixelSpacing 0.5547 × f=4, SliceThickness 1.5 × k=5 — keeps the real-world size, so G-code scale holds.
        assertEquals(2.2188, 0.5547 * 4, 1e-9)
        assertEquals(7.5, 1.5 * 5, 1e-9)
    }

    // ── Round-trip on real data (guarded; mirrors Dcm4cheLoaderTest) ──────────

    @Test
    fun `ensureDownsampled produces a valid 128-cube series`() = runBlocking {
        assumeTrue("sample DICOM data not present — skipping", File(DICOM_FOLDER).exists())
        val cacheRoot = Files.createTempDirectory("sam3d-ds-test")
        val downsampler = Dcm4cheDownsampler(cacheRoot = cacheRoot)

        val outPath = downsampler.ensureDownsampled(DICOM_FOLDER, 128)
        val outDir = File(outPath)
        assertTrue(outDir.isDirectory, "output folder must exist")
        assertTrue(File(outDir, ".done").exists(), ".done marker must be written")

        val slices = outDir.listFiles { _, n -> n.endsWith(".dcm") }!!.sortedBy { it.name }
        assertEquals(113, slices.size, "ceil(562/5) output slices")

        val seriesUids = HashSet<String>()
        val sopUids = HashSet<String>()
        for (file in slices) {
            DicomInputStream(file).use { dis ->
                dis.includeBulkData = DicomInputStream.IncludeBulkData.YES
                val a = dis.readDataset(-1, -1)
                assertEquals(128, a.getInt(Tag.Rows, 0), "Rows in ${file.name}")
                assertEquals(128, a.getInt(Tag.Columns, 0), "Columns in ${file.name}")
                assertEquals(16, a.getInt(Tag.BitsAllocated, 0), "BitsAllocated")
                assertEquals("MONOCHROME2", a.getString(Tag.PhotometricInterpretation))
                assertEquals(128 * 128 * 2, a.getSafeBytes(Tag.PixelData)!!.size, "PixelData length")
                seriesUids += a.getString(Tag.SeriesInstanceUID)
                sopUids += a.getString(Tag.SOPInstanceUID)
            }
        }
        assertEquals(1, seriesUids.size, "all slices must share ONE SeriesInstanceUID (sitk grouping)")
        assertEquals(slices.size, sopUids.size, "every slice must have a UNIQUE SOPInstanceUID")

        // Spacing scaled so physical extent is preserved (→ correct G-code scale).
        DicomInputStream(slices.first()).use { dis ->
            val a = dis.readDatasetUntilPixelData()
            val ps = a.getDoubles(Tag.PixelSpacing)!!
            assertEquals(0.5546875 * 4, ps[0], 1e-6)
            assertEquals(1.5 * 5, a.getDouble(Tag.SliceThickness, 0.0), 1e-6)
        }
    }

    @Test
    fun `downsampled folder loads as a 128-cube (app cube == engine cube)`() = runBlocking {
        assumeTrue("sample DICOM data not present — skipping", File(DICOM_FOLDER).exists())
        val cacheRoot = Files.createTempDirectory("sam3d-ds-test")
        val outPath = Dcm4cheDownsampler(cacheRoot = cacheRoot).ensureDownsampled(DICOM_FOLDER, 128)
        val series = Dcm4cheLoader().loadSeries(outPath)
        assertEquals(128, series.cubeSize, "max(128,128,113) → 128; matches what the engine's load3dmatrix builds")
    }

    @Test
    fun `second call hits the cache without rewriting`() = runBlocking {
        assumeTrue("sample DICOM data not present — skipping", File(DICOM_FOLDER).exists())
        val cacheRoot = Files.createTempDirectory("sam3d-ds-test")
        val downsampler = Dcm4cheDownsampler(cacheRoot = cacheRoot)

        val first = downsampler.ensureDownsampled(DICOM_FOLDER, 128)
        val stamp = File(first, "slice_0000.dcm").lastModified()
        val second = downsampler.ensureDownsampled(DICOM_FOLDER, 128)
        assertEquals(first, second, "same source + target ⇒ same cached folder")
        assertEquals(stamp, File(second, "slice_0000.dcm").lastModified(), "cached run must not rewrite slices")
    }

    companion object {
        private const val DICOM_FOLDER = "/Users/DaChelimo/Documents/Research/Sample-Data/00000304"
    }
}
