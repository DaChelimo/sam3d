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
 *
 * The [eta] values are **ranges, not point estimates**, because they span an order of magnitude with
 * the hardware. PyPI's Windows PyTorch wheels are CPU-only, so the lab desktops this ships to run SAM
 * inference many times slower than the developer Mac the original "≈ 3–4 hr" figure came from. A user
 * who starts a Production run on a promise of "3–4 hr" and finds it still going the next morning
 * assumes it has hung and kills it — an honest upper bound is worth more than a flattering average.
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
        eta = "15 min – 1 hr",
        description = "Shrinks the scan to a medium cube (~256 voxels) and runs 8 slices — " +
            "a quick preview with enough detail to read the anatomy, for testing the workflow end to end.",
    ),
    PRODUCTION(
        label = "Production",
        slices = 120,
        downsampleTargetMaxDim = null,
        eta = "4 hr – overnight",
        description = "Full-resolution scan with 120 slices — for the final, print-ready scaffold. " +
            "Plan for this to run overnight on a CPU-only machine.",
    );

    val downsamples: Boolean get() = downsampleTargetMaxDim != null

    companion object {
        /** Resolve a persisted enum name (case-insensitive), falling back to [PRODUCTION]. */
        fun fromNameOrDefault(name: String?): QualityPreset =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PRODUCTION
    }
}
