package edu.upenn.sam3d.domain.model

enum class PipelineStage(val label: String) {
    LOADING_DICOM("Loading DICOM volume"),
    PREPARING_SLICES("Preparing slice views"),
    RUNNING_INFERENCE("Running SAM inference"),
    BUILDING_POINT_CLOUD("Building point cloud"),
    GENERATING_GCODE("Generating G-code"),
    COMPLETE("Complete"),
    ERROR("Error")
}
