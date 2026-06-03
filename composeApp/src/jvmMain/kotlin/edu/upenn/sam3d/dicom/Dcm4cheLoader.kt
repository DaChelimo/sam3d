package edu.upenn.sam3d.dicom

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import edu.upenn.sam3d.AppConfig
import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.domain.model.DicomSeries
import edu.upenn.sam3d.domain.repository.DicomRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.io.DicomInputStream
import java.awt.image.BufferedImage
import java.io.File

// ── Standalone helpers (internal so jvmTest can import them) ─────────────────

/**
 * Mirrors Python utils.padtocube (lines 41-51).
 * Returns the padded ByteArray and the cube side length S.
 * Layout: src[h, w, n] = src[h*dimW*dimN + w*dimN + n].
 */
internal fun padToCube(src: ByteArray, dimH: Int, dimW: Int, dimN: Int): Pair<ByteArray, Int> {
    val s = maxOf(dimH, dimW, dimN)
    if (dimH == s && dimW == s && dimN == s) return Pair(src, s)

    val leftH = (s - dimH) / 2
    val leftW = (s - dimW) / 2
    val leftN = (s - dimN) / 2

    val dst = ByteArray(s * s * s)  // zero-filled
    for (h in 0 until dimH) {
        val dstH = h + leftH
        for (w in 0 until dimW) {
            val dstW = w + leftW
            val srcBase = h * dimW * dimN + w * dimN
            val dstBase = dstH * s * s + dstW * s + leftN
            src.copyInto(dst, dstBase, srcBase, srcBase + dimN)
        }
    }
    return Pair(dst, s)
}

/**
 * §14 OOM guard. The padded cube is an S³ ByteArray held whole in memory; a pathological series
 * (e.g. S≈1300+) would blow past the heap. Throws a clear, user-facing error before allocation if
 * S³ exceeds [maxBytes]. Pure & internal so it's unit-testable without loading real DICOMs.
 */
/**
 * Maps each cube axis to the anatomical plane you see when slicing along it, from DICOM
 * `ImageOrientationPatient` (IOP). Returns labels indexed [AXIS_0, AXIS_1, AXIS_2]. The plane is
 * named by the normal of the viewed slice: ‖patient-Z → Axial, ‖Y → Coronal, ‖X → Sagittal.
 *
 * Array is [H=rows, W=cols, N=slices]; AXIS_0 fixes H (normal = column-dir), AXIS_1 fixes W
 * (normal = row-dir), AXIS_2 fixes N (normal = row×col). Falls back to the conventional
 * axial-acquisition mapping when IOP is absent. Pure/internal so it's unit-testable.
 */
internal fun anatomicalPlanes(iop: DoubleArray?): List<String> {
    if (iop == null || iop.size < 6) return listOf("Coronal", "Sagittal", "Axial")
    val rowDir = doubleArrayOf(iop[0], iop[1], iop[2])   // along increasing column (X)
    val colDir = doubleArrayOf(iop[3], iop[4], iop[5])   // along increasing row (Y)
    val sliceNormal = doubleArrayOf(
        rowDir[1] * colDir[2] - rowDir[2] * colDir[1],
        rowDir[2] * colDir[0] - rowDir[0] * colDir[2],
        rowDir[0] * colDir[1] - rowDir[1] * colDir[0],
    )
    return listOf(planeOfNormal(colDir), planeOfNormal(rowDir), planeOfNormal(sliceNormal))
}

private fun planeOfNormal(n: DoubleArray): String {
    val ax = kotlin.math.abs(n[0]); val ay = kotlin.math.abs(n[1]); val az = kotlin.math.abs(n[2])
    return when {
        az >= ax && az >= ay -> "Axial"
        ay >= ax -> "Coronal"
        else -> "Sagittal"
    }
}

internal fun requireCubeWithinLimit(cubeSize: Int, maxBytes: Long = AppConfig.MAX_CUBE_BYTES) {
    val bytes = cubeSize.toLong() * cubeSize.toLong() * cubeSize.toLong()
    require(bytes <= maxBytes) {
        "DICOM volume too large: the padded cube would be ${cubeSize}³ ≈ ${bytes / (1024 * 1024)} MB, " +
            "above the ${maxBytes / (1024 * 1024 * 1024)} GB limit. Increase the JVM -Xmx in " +
            "gradle.properties, use a cropped series, or contact support."
    }
}

/**
 * Global min-max normalisation. Mirrors Python utils.load3dmatrix line 28:
 *   image = (image - np.amin(image)) / (np.amax(image) - np.amin(image)) * 255
 */
internal fun normaliseVolume(rawPixels: FloatArray): ByteArray {
    val min = rawPixels.min()
    val max = rawPixels.max()
    val range = (max - min).coerceAtLeast(1f)
    return ByteArray(rawPixels.size) { i ->
        ((rawPixels[i] - min) / range * 255f).toInt().coerceIn(0, 255).toByte()
    }
}

// ── Dcm4cheLoader ─────────────────────────────────────────────────────────────

class Dcm4cheLoader : DicomRepository {

    override suspend fun loadSeries(folderPath: String): DicomSeries = withContext(Dispatchers.IO) {
        val folder = File(folderPath)
        val dcmFiles = folder.listFiles { _, name -> name.endsWith(".dcm", ignoreCase = true) }
            ?.toList() ?: emptyList()
        require(dcmFiles.isNotEmpty()) { "No .dcm files found in $folderPath" }

        // Pass 1: read metadata only to determine z-order, dimensions, and orientation.
        data class FileMeta(val file: File, val z: Double, val rows: Int, val cols: Int, val iop: DoubleArray?)

        val metas: List<FileMeta> = dcmFiles.mapNotNull { file ->
            try {
                DicomInputStream(file).use { dis ->
                    val attrs = dis.readDatasetUntilPixelData()
                    val r = attrs.getInt(Tag.Rows, 0)
                    val c = attrs.getInt(Tag.Columns, 0)
                    if (r == 0 || c == 0) null
                    else FileMeta(file, zCoordOf(attrs, file.name), r, c, attrs.getDoubles(Tag.ImageOrientationPatient))
                }
            } catch (_: Exception) { null }
        }.sortedBy { it.z }

        require(metas.isNotEmpty()) { "No valid DICOM slices found in $folderPath" }

        val rows = metas.first().rows
        val cols = metas.first().cols
        val numSlices = metas.size
        val rawShape = Triple(rows, cols, numSlices)

        // §14: bail before the big allocations if the padded cube would exceed the memory limit.
        requireCubeWithinLimit(maxOf(rows, cols, numSlices))

        // Pass 2: allocate a single H×W×N volume array, fill slice by slice.
        // Row-major: idx = h*W*N + w*N + n
        val volume = FloatArray(rows * cols * numSlices)
        for ((n, meta) in metas.withIndex()) {
            try {
                DicomInputStream(meta.file).use { dis ->
                    dis.includeBulkData = DicomInputStream.IncludeBulkData.YES
                    val attrs = dis.readDataset(-1, -1)
                    val rawBytes = attrs.getSafeBytes(Tag.PixelData) ?: return@use
                    if (rawBytes.isEmpty()) return@use
                    val bitsAllocated = attrs.getInt(Tag.BitsAllocated, 16)
                    val pixelRepresentation = attrs.getInt(Tag.PixelRepresentation, 0)
                    for (h in 0 until rows) {
                        for (w in 0 until cols) {
                            val pixIdx = h * cols + w
                            volume[h * cols * numSlices + w * numSlices + n] =
                                pixelValue(rawBytes, pixIdx, bitsAllocated, pixelRepresentation)
                        }
                    }
                }
            } catch (_: Exception) { /* leave zeros for this slice */ }
        }

        val normalised = normaliseVolume(volume)
        val (cube, cubeSize) = padToCube(normalised, rows, cols, numSlices)

        DicomSeries(
            folderPath = folderPath, cubeSize = cubeSize, rawShape = rawShape, cube = cube,
            axisPlanes = anatomicalPlanes(metas.firstOrNull()?.iop),
        )
    }

    override suspend fun loadSliceBitmap(
        series: DicomSeries,
        axis: Axis,
        index: Int
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        val s = series.cubeSize
        val cube = series.cube
        if (index < 0 || index >= s || cube.isEmpty()) return@withContext null

        val argb = IntArray(s * s)
        when (axis) {
            Axis.AXIS_0 -> {
                // Fixed H=index; view (W, N) plane: imgX=w, imgY=n
                val hBase = index * s * s
                for (n in 0 until s) {
                    for (w in 0 until s) {
                        val v = cube[hBase + w * s + n].toInt() and 0xFF
                        argb[n * s + w] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
            }
            Axis.AXIS_1 -> {
                // Fixed W=index; view (H, N) plane: imgX=h, imgY=n
                for (n in 0 until s) {
                    for (h in 0 until s) {
                        val v = cube[h * s * s + index * s + n].toInt() and 0xFF
                        argb[n * s + h] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
            }
            Axis.AXIS_2 -> {
                // Fixed N=index; view (H, W) plane: imgX=w, imgY=h
                for (h in 0 until s) {
                    val hBase = h * s * s
                    for (w in 0 until s) {
                        val v = cube[hBase + w * s + index].toInt() and 0xFF
                        argb[h * s + w] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
            }
        }

        val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, s, s, argb, 0, s)
        img.toComposeImageBitmap()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun zCoordOf(attrs: Attributes, filename: String): Double {
        val ipp = attrs.getDoubles(Tag.ImagePositionPatient)
        if (ipp != null && ipp.size >= 3) return ipp[2]
        val loc = attrs.getDouble(Tag.SliceLocation, Double.NaN)
        if (!loc.isNaN()) return loc
        // Fall back to filename for lexicographic sort (same as Python fallback)
        return filename.hashCode().toDouble()
    }

    private fun pixelValue(rawBytes: ByteArray, i: Int, bitsAllocated: Int, pixelRepresentation: Int): Float {
        return if (bitsAllocated == 8) {
            val raw = rawBytes[i].toInt() and 0xFF
            if (pixelRepresentation == 1 && raw and 0x80 != 0) (raw or 0xFFFFFF00.toInt()).toFloat()
            else raw.toFloat()
        } else {
            // 16-bit, little-endian (standard uncompressed DICOM)
            val lo = rawBytes[i * 2].toInt() and 0xFF
            val hi = rawBytes[i * 2 + 1].toInt() and 0xFF
            val raw = lo or (hi shl 8)
            if (pixelRepresentation == 1 && raw and 0x8000 != 0) (raw or 0xFFFF0000.toInt()).toFloat()
            else raw.toFloat()
        }
    }
}
