package edu.upenn.sam3d.domain.usecase

/**
 * Port (commonMain): produces a downsampled COPY of a DICOM series so the slow engine runs on a small
 * cube. Implemented in jvmMain ([edu.upenn.sam3d.dicom.Dcm4cheDownsampler]) to keep dcm4che / java.nio
 * out of commonMain (Critical rule #4).
 *
 * Returns the absolute path of a folder holding the downsampled `.dcm` series. The same path is fed to
 * BOTH the annotation loader and the engine `-p`, so the cube the user annotates on is identical to
 * the cube the engine segments — keeping `points.json` voxel coordinates valid with no rescaling.
 *
 * Implementations should cache/reuse the result across calls (same source + target ⇒ same folder, no
 * rewrite) since this is invoked whenever the folder or quality changes.
 */
fun interface DicomDownsampler {
    /** @param targetMaxDim desired longest cube side, in voxels (e.g. 128). */
    suspend fun ensureDownsampled(sourceFolder: String, targetMaxDim: Int): String
}
