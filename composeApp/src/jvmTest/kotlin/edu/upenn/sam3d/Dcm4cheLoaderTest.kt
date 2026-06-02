package edu.upenn.sam3d

import edu.upenn.sam3d.dicom.Dcm4cheLoader
import edu.upenn.sam3d.dicom.normaliseVolume
import edu.upenn.sam3d.dicom.padToCube
import edu.upenn.sam3d.domain.model.Axis
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class Dcm4cheLoaderTest {

    // ── STEP 1: padToCube unit tests ──────────────────────────────────────────

    @Test
    fun `padToCube - (3,4,5) produces cube of side 5`() {
        val src = ByteArray(3 * 4 * 5) { it.toByte() }
        val (dst, s) = padToCube(src, 3, 4, 5)
        assertEquals(5, s)
        assertEquals(5 * 5 * 5, dst.size)
    }

    @Test
    fun `padToCube - (3,4,5) data preserved at correct padded positions`() {
        // src[h, w, n] = (h*10 + w).toByte()
        val dimH = 3; val dimW = 4; val dimN = 5
        val src = ByteArray(dimH * dimW * dimN) { i ->
            val h = i / (dimW * dimN)
            val w = (i / dimN) % dimW
            (h * 10 + w).toByte()
        }
        val (dst, s) = padToCube(src, dimH, dimW, dimN)
        // s=5: leftH=(5-3)/2=1, leftW=(5-4)/2=0, leftN=(5-5)/2=0
        val leftH = (s - dimH) / 2
        val leftW = (s - dimW) / 2
        val leftN = (s - dimN) / 2

        // src[0,0,0] → dst[leftH, leftW, leftN]
        assertEquals(src[0], dst[leftH * s * s + leftW * s + leftN])
        // src[2,3,4] → dst[2+leftH, 3+leftW, 4+leftN]
        val srcVal = src[2 * dimW * dimN + 3 * dimN + 4]
        assertEquals(srcVal, dst[(2 + leftH) * s * s + (3 + leftW) * s + (4 + leftN)])
    }

    @Test
    fun `padToCube - (3,4,5) zero padding in pad regions`() {
        val src = ByteArray(3 * 4 * 5) { 42 }
        val (dst, s) = padToCube(src, 3, 4, 5)
        // H is padded by 1 on each side → rows 0 and 4 must be zero
        for (w in 0 until s) for (n in 0 until s) {
            assertEquals(0.toByte(), dst[0 * s * s + w * s + n], "H=0 pad")
            assertEquals(0.toByte(), dst[4 * s * s + w * s + n], "H=4 pad")
        }
        // W is padded by 1 on the right side only → column 4 must be zero
        for (h in 0 until s) for (n in 0 until s) {
            assertEquals(0.toByte(), dst[h * s * s + 4 * s + n], "W=4 pad")
        }
    }

    @Test
    fun `padToCube - cubic (10,10,10) input is unchanged`() {
        val src = ByteArray(10 * 10 * 10) { it.toByte() }
        val (dst, s) = padToCube(src, 10, 10, 10)
        assertEquals(10, s)
        assertContentEquals(src, dst)
    }

    // ── STEP 3: normalisation unit tests ─────────────────────────────────────

    @Test
    fun `normaliseVolume - minimum output pixel is 0`() {
        val input = FloatArray(10) { it.toFloat() }   // [0f..9f]
        val output = normaliseVolume(input)
        val minUnsigned = output.minOf { it.toInt() and 0xFF }
        assertEquals(0, minUnsigned)
    }

    @Test
    fun `normaliseVolume - maximum output pixel is 255`() {
        val input = FloatArray(10) { it.toFloat() }
        val output = normaliseVolume(input)
        val maxUnsigned = output.maxOf { it.toInt() and 0xFF }
        assertEquals(255, maxUnsigned)
    }

    @Test
    fun `normaliseVolume - formula matches Python utils line 28`() {
        // Python: (pixel - min) / (max - min) * 255
        val input = FloatArray(10) { it.toFloat() }  // min=0, max=9
        val output = normaliseVolume(input)
        // For pixel=5: (5-0)/(9-0)*255 = 141.666… → 141
        val expected = ((5f - 0f) / (9f - 0f) * 255f).toInt().coerceIn(0, 255)
        assertEquals(expected, output[5].toInt() and 0xFF)
    }

    // ── STEP 7: integration tests with real DICOM series ─────────────────────

    @Test
    fun `loadSeries returns valid DicomSeries for 00000304 data`() = runBlocking {
        val loader = Dcm4cheLoader()
        val series = loader.loadSeries(DICOM_FOLDER)
        assertTrue(series.cubeSize > 0, "cubeSize must be > 0")
        assertTrue(series.rawShape.first > 0, "H must be > 0")
        assertTrue(series.rawShape.second > 0, "W must be > 0")
        assertTrue(series.rawShape.third > 0, "N must be > 0")
        println("cubeSize S = ${series.cubeSize}")
        println("rawShape H×W×N = ${series.rawShape.first}×${series.rawShape.second}×${series.rawShape.third}")
    }

    @Test
    fun `loadSliceBitmap on AXIS_2 mid-slice returns non-null ImageBitmap`() = runBlocking {
        val loader = Dcm4cheLoader()
        val series = loader.loadSeries(DICOM_FOLDER)
        val midSlice = series.cubeSize / 2
        val bitmap = loader.loadSliceBitmap(series, Axis.AXIS_2, midSlice)
        assertNotNull(bitmap, "bitmap must not be null")
        assertEquals(series.cubeSize, bitmap.width)
        assertEquals(series.cubeSize, bitmap.height)
    }

    companion object {
        private const val DICOM_FOLDER = "/Users/DaChelimo/Documents/Research/Sample-Data/00000304"
    }
}
