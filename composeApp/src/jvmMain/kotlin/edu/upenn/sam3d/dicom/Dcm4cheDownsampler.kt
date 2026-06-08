package edu.upenn.sam3d.dicom

import edu.upenn.sam3d.domain.usecase.DicomDownsampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.util.UIDUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

// ── Pure, testable helpers (internal so jvmTest can import them) ─────────────────

internal data class DownsampleFactors(
    val f: Int,          // in-plane integer decimation factor (rows & cols)
    val k: Int,          // z (slice) averaging stride
    val newRows: Int,
    val newCols: Int,
    val outSlices: Int,
)

internal fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

/**
 * Pick integer factors so the downsampled cube's longest side is ≈ [targetMaxDim]. Integer-only so
 * each output voxel is a clean f×f (in-plane) / k (z) box average — no interpolation kernel. Trailing
 * rows/cols that don't divide evenly are cropped by the averaging loop (newRows = rows / f).
 */
internal fun computeFactors(rows: Int, cols: Int, slices: Int, targetMaxDim: Int): DownsampleFactors {
    require(targetMaxDim > 0) { "targetMaxDim must be > 0" }
    val f = ceilDiv(maxOf(rows, cols), targetMaxDim).coerceAtLeast(1)
    val k = ceilDiv(slices, targetMaxDim).coerceAtLeast(1)
    val newRows = rows / f
    val newCols = cols / f
    val outSlices = ceilDiv(slices, k)
    return DownsampleFactors(f, k, newRows, newCols, outSlices)
}

/** Single-slice in-plane box average: src[rows*cols] → dst[(rows/f)*(cols/f)], each pixel the f×f mean. */
internal fun boxAverageInPlane(src: IntArray, rows: Int, cols: Int, f: Int): IntArray {
    val nr = rows / f
    val nc = cols / f
    val dst = IntArray(nr * nc)
    for (or in 0 until nr) {
        for (oc in 0 until nc) {
            var sum = 0
            val baseR = or * f
            val baseC = oc * f
            for (dr in 0 until f) {
                val rowBase = (baseR + dr) * cols + baseC
                for (dc in 0 until f) sum += src[rowBase + dc]
            }
            dst[or * nc + oc] = sum / (f * f)
        }
    }
    return dst
}

// ── Dcm4cheDownsampler ──────────────────────────────────────────────────────────

/**
 * Writes a downsampled COPY of a DICOM series (valid, SimpleITK-readable) so the engine's cube-padded
 * pipeline runs on a small cube. The same folder feeds the annotation canvas and the engine `-p`, so
 * the cubes match and `points.json` coordinates stay correct (see [DicomDownsampler]).
 *
 * Mechanics: sort slices by z (ImagePositionPatient[2]); pick integer factors ([computeFactors]);
 * f×f in-plane + k-deep box-average; clone each group's first slice and override Rows/Columns,
 * PixelData (16-bit LE, VR.OW), PixelSpacing (×f) and SliceThickness (×k) so the printed scaffold
 * keeps its real-world size, plus one shared SeriesInstanceUID and a unique SOPInstanceUID per slice.
 *
 * Results are cached under the system temp dir keyed by (source, factors); a `.done` marker means a
 * complete folder, so repeat calls (quality flip-flop, re-entering Prompting) return instantly.
 */
class Dcm4cheDownsampler(
    private val cacheRoot: Path = Path.of(System.getProperty("java.io.tmpdir"), "SAM3D", "downsampled"),
) : DicomDownsampler {

    override suspend fun ensureDownsampled(sourceFolder: String, targetMaxDim: Int): String =
        withContext(Dispatchers.IO) {
            val dcmFiles = File(sourceFolder).listFiles { _, name -> name.endsWith(".dcm", ignoreCase = true) }
                ?.toList() ?: emptyList()
            require(dcmFiles.isNotEmpty()) { "No .dcm files found in $sourceFolder" }

            // Pass 1: metadata only, z-sorted (mirror Dcm4cheLoader's ordering exactly).
            data class Meta(val file: File, val z: Double, val rows: Int, val cols: Int)
            val metas = dcmFiles.mapNotNull { file ->
                runCatching {
                    DicomInputStream(file).use { dis ->
                        val a = dis.readDatasetUntilPixelData()
                        val r = a.getInt(Tag.Rows, 0)
                        val c = a.getInt(Tag.Columns, 0)
                        if (r == 0 || c == 0) null else Meta(file, zCoordOf(a, file.name), r, c)
                    }
                }.getOrNull()
            }.sortedBy { it.z }
            require(metas.isNotEmpty()) { "No valid DICOM slices found in $sourceFolder" }

            val rows = metas.first().rows
            val cols = metas.first().cols
            val n = metas.size
            val factors = computeFactors(rows, cols, n, targetMaxDim)

            // Cache, guarded by a .done marker (a half-written/crashed folder is rebuilt).
            val key = sha1("${File(sourceFolder).absolutePath}|${factors.f}|${factors.k}|$targetMaxDim")
            val outDir = cacheRoot.resolve(key)
            val doneMarker = outDir.resolve(".done")
            if (Files.exists(doneMarker)) return@withContext outDir.toString()
            if (Files.exists(outDir)) outDir.toFile().deleteRecursively()
            Files.createDirectories(outDir)

            val seriesUid = UIDUtils.createUID()
            var outIndex = 0
            var groupStart = 0
            while (groupStart < n) {
                val groupEnd = minOf(groupStart + factors.k, n)
                val template = readDataset(metas[groupStart].file)   // first-of-group: metadata + pixels

                // Accumulate f×f box sums across every source slice in the group.
                val accum = IntArray(factors.newRows * factors.newCols)
                for (gi in groupStart until groupEnd) {
                    val attrs = if (gi == groupStart) template else readDataset(metas[gi].file)
                    val px = readPixels(attrs, rows, cols)
                    accumulateBoxSums(px, cols, factors, accum)
                }
                val divisor = factors.f * factors.f * (groupEnd - groupStart)
                val pixelBytes = ByteArray(factors.newRows * factors.newCols * 2)
                for (i in accum.indices) {
                    val v = accum[i] / divisor                       // averaged stored value
                    pixelBytes[i * 2] = (v and 0xFF).toByte()        // little-endian, signed-safe
                    pixelBytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }

                writeSlice(
                    template = template,
                    pixelBytes = pixelBytes,
                    factors = factors,
                    seriesUid = seriesUid,
                    index = outIndex,
                    dst = outDir.resolve("slice_%04d.dcm".format(outIndex)).toFile(),
                )
                outIndex++
                groupStart += factors.k
            }

            Files.writeString(doneMarker, "rows=${factors.newRows} cols=${factors.newCols} slices=$outIndex\n")
            outDir.toString()
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun readDataset(file: File): Attributes =
        DicomInputStream(file).use { dis ->
            dis.includeBulkData = DicomInputStream.IncludeBulkData.YES
            dis.readDataset(-1, -1)
        }

    /** Decode raw PixelData to signed/unsigned stored values (mirrors Dcm4cheLoader.pixelValue). */
    private fun readPixels(attrs: Attributes, rows: Int, cols: Int): IntArray {
        val raw = attrs.getSafeBytes(Tag.PixelData) ?: return IntArray(rows * cols)
        val bits = attrs.getInt(Tag.BitsAllocated, 16)
        val signed = attrs.getInt(Tag.PixelRepresentation, 0) == 1
        val out = IntArray(rows * cols)
        if (bits == 8) {
            for (i in out.indices) {
                val r = raw[i].toInt() and 0xFF
                out[i] = if (signed && r and 0x80 != 0) r or -0x100 else r
            }
        } else {
            for (i in out.indices) {
                val lo = raw[i * 2].toInt() and 0xFF
                val hi = raw[i * 2 + 1].toInt() and 0xFF
                var v = lo or (hi shl 8)
                if (signed && v and 0x8000 != 0) v = v or -0x10000
                out[i] = v
            }
        }
        return out
    }

    private fun accumulateBoxSums(px: IntArray, cols: Int, f: DownsampleFactors, accum: IntArray) {
        for (or in 0 until f.newRows) {
            for (oc in 0 until f.newCols) {
                var sum = 0
                val baseR = or * f.f
                val baseC = oc * f.f
                for (dr in 0 until f.f) {
                    val rowBase = (baseR + dr) * cols + baseC
                    for (dc in 0 until f.f) sum += px[rowBase + dc]
                }
                accum[or * f.newCols + oc] += sum
            }
        }
    }

    private fun writeSlice(
        template: Attributes,
        pixelBytes: ByteArray,
        factors: DownsampleFactors,
        seriesUid: String,
        index: Int,
        dst: File,
    ) {
        val out = Attributes(template)                               // deep copy of all metadata
        out.setInt(Tag.Rows, VR.US, factors.newRows)
        out.setInt(Tag.Columns, VR.US, factors.newCols)
        out.setBytes(Tag.PixelData, VR.OW, pixelBytes)              // OW: required for 16-bit

        // Scale spacing so the physical extent — and therefore the G-code scale — is preserved.
        template.getDoubles(Tag.PixelSpacing)?.takeIf { it.size >= 2 }?.let {
            out.setDouble(Tag.PixelSpacing, VR.DS, it[0] * factors.f, it[1] * factors.f)
        }
        val st = template.getDouble(Tag.SliceThickness, Double.NaN)
        if (!st.isNaN()) out.setDouble(Tag.SliceThickness, VR.DS, st * factors.k)
        // ImagePositionPatient is left as the template's (first-of-group) value → consecutive output
        // slices step by k×original spacing = the new SliceThickness, a consistent geometry for sitk.

        out.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid)     // one series for all slices
        out.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID())  // unique per slice
        out.setInt(Tag.InstanceNumber, VR.IS, index + 1)
        // Stale min/max-pixel hints would no longer match the resampled data — drop them.
        out.remove(Tag.SmallestImagePixelValue)
        out.remove(Tag.LargestImagePixelValue)

        val fmi = out.createFileMetaInformation(UID.ExplicitVRLittleEndian)
        DicomOutputStream(dst).use { it.writeDataset(fmi, out) }
    }

    private fun zCoordOf(attrs: Attributes, filename: String): Double {
        val ipp = attrs.getDoubles(Tag.ImagePositionPatient)
        if (ipp != null && ipp.size >= 3) return ipp[2]
        val loc = attrs.getDouble(Tag.SliceLocation, Double.NaN)
        if (!loc.isNaN()) return loc
        return filename.hashCode().toDouble()
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
