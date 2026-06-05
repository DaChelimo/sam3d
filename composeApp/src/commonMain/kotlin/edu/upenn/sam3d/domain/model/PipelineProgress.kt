package edu.upenn.sam3d.domain.model

data class PipelineProgress(
    val stage: PipelineStage,
    val stagePercentage: Float = 0f,
    val elapsedSeconds: Long = 0L,
    val outputPath: String? = null,
    // Human-readable "what's running right now" line built from stdout (e.g. "Extracting paths: 4314 / 8430").
    val detail: String? = null,
    // Seconds remaining in the CURRENT stage, parsed from tqdm's own "[mm:ss<mm:ss]" estimate when
    // present (null otherwise). Used by the UI to show an ETA without guessing.
    val etaSeconds: Long? = null,
)
