package edu.upenn.sam3d.domain.model

/**
 * The user-facing "pipeline quality" choice. It bundles the TWO levers that must move together:
 *
 *  - [slices]: sam3d.py's `-s` count — how many 2D planes per rotation are fed to SAM inference.
 *  - [downsampleTargetMaxDim]: if non-null, the input DICOM is downsampled so its padded cube's
 *    longest side is ~this many voxels *before* both the annotation canvas and the engine see it.
 *    `null` = keep the scan at full resolution.
 *
 * Why both: the pipeline's real cost is upstream of `-s`. The engine pads the volume to a cube and
 * builds 6 rotated copies in RAM (a 562³ input → six 766³ float64 arrays ≈ 21.6 GB → swap thrash on
 * a 16 GB Mac), which `-s` does not touch. Shrinking the cube is the only lever that helps, so DRAFT
 * downsamples the input *and* drops the slice count for a fast smoke test; PRODUCTION leaves the scan
 * untouched for the final scaffold.
 */
enum class QualityPreset(
    val label: String,
    val slices: Int,
    val downsampleTargetMaxDim: Int?,
    val eta: String,
    val description: String,
) {
    DRAFT(
        label = "Draft",
        slices = 8,
        downsampleTargetMaxDim = 256,
        eta = "≈ 5–12 min",
        description = "Shrinks the scan to a medium cube (~256 voxels) and runs 8 slices — " +
            "a quick preview with enough detail to read the anatomy, for testing the workflow end to end.",
    ),
    PRODUCTION(
        label = "Production",
        slices = 120,
        downsampleTargetMaxDim = null,
        eta = "≈ 3–4 hr",
        description = "Full-resolution scan with 120 slices — for the final, print-ready scaffold.",
    );

    val downsamples: Boolean get() = downsampleTargetMaxDim != null

    companion object {
        /** Resolve a persisted enum name (case-insensitive), falling back to [PRODUCTION]. */
        fun fromNameOrDefault(name: String?): QualityPreset =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PRODUCTION
    }
}
