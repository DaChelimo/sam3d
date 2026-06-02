package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.PipelineStage

object AppConfig {
    const val MAX_CACHED_BITMAPS = 256
    const val MAX_CUBE_BYTES = 2L * 1024 * 1024 * 1024  // 2 GB hard limit
    const val PIPELINE_TIMEOUT_MS = 30 * 60 * 1000L     // 30 minutes

    object PipelineDefaults {
        const val ROTATIONS = "ico"
        const val SLICES = 120   // matches sam3d.py default; use 8 for fast dev smoke tests
        const val SAM_VERSION = 1
        const val CHECKPOINT = "checkpoints/sam_vit_h_4b8939.pth"
        const val DATATYPE = "dcm"
    }

    /**
     * Local-dev prefill for the Start screen (the values this machine always uses). The Start
     * screen seeds empty fields from these; they remain editable. PYTHON must be the `sam3d` conda
     * env — base anaconda / `python3` lacks pydicom et al. and the pipeline fails to import.
     * (v2: move these to the per-user config.json described in §11.3.)
     */
    object DevDefaults {
        const val SAM3D_GCODE_DIR = "/Users/DaChelimo/Documents/Research/SAM3D-GCODE"
        const val DICOM_FOLDER = "/Users/DaChelimo/Documents/Research/Sample-Data/00000304"
        const val OUTPUT_FOLDER = "/Users/DaChelimo/Documents/Research/OUTPUT"
        const val PYTHON = "/opt/anaconda3/envs/sam3d/bin/python"
    }

    /**
     * stdout substrings (lowercase) that flip the pipeline stage, in pipeline order. Centralised
     * here per §6.2 so updating them when sam3d.py changes its prints is a one-line edit. Captured
     * from a real run — see composeApp/src/jvmTest/resources/fixtures/dry_run_annotated.log.
     *
     * NOTE: the per-stage tqdm progress lines (e.g. " 33%|…| 2/6") carry no stage word, so
     * StdoutProgressParser is stateful and attributes a bare tqdm % to the most recent stage here.
     */
    object ProgressMarkers {
        val STAGE_MARKERS: List<Pair<String, PipelineStage>> = listOf(
            "transforms made" to PipelineStage.LOADING_DICOM,
            "image loaded" to PipelineStage.PREPARING_SLICES,
            "making prompt slices" to PipelineStage.PREPARING_SLICES,
            "model loaded" to PipelineStage.RUNNING_INFERENCE,
            "number of points" to PipelineStage.BUILDING_POINT_CLOUD,
            "point cloud" to PipelineStage.BUILDING_POINT_CLOUD,
            "variable density printing pipeline started" to PipelineStage.GENERATING_GCODE,
            "executing voxels2gcode" to PipelineStage.GENERATING_GCODE,
            "gcode generated" to PipelineStage.COMPLETE,
        )
    }
}
