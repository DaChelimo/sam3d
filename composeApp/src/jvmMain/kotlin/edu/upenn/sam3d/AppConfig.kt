package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.domain.model.UserConfig

object AppConfig {
    const val MAX_CACHED_BITMAPS = 256
    const val MAX_CUBE_BYTES = 2L * 1024 * 1024 * 1024  // 2 GB hard limit

    // §14 / sleep-resilience: the pipeline is killed only after it stops making progress (no new
    // stdout) for this long — NOT after a fixed total time, since a real Production run is hours and
    // a healthy long run must not be culled. Sleep is detected and excluded (see PythonProcessManager).
    const val INACTIVITY_TIMEOUT_MS = 20 * 60 * 1000L   // 20 min with no output → assume stuck
    const val MAX_RUN_MS = 12 * 60 * 60 * 1000L         // 12 h of *active* time — absolute backstop

    object PipelineDefaults {
        const val ROTATIONS = "ico"
        const val SLICES = 120   // matches sam3d.py default; use 8 for fast dev smoke tests
        const val SAM_VERSION = 1
        const val CHECKPOINT = "checkpoints/sam_vit_h_4b8939.pth"
        const val DATATYPE = "dcm"
    }

    /**
     * Per-user config (§11.3), loaded once from <userDataDir>/SAM3D/config.json (then the bundled
     * config.default.json, then these fallbacks). Replaces the previously hardcoded DevDefaults so
     * paths / the python env are edited in a file, not in source. The Start screen seeds empty
     * fields from these.
     */
    private val userConfig: UserConfig by lazy { ConfigLoader.load() }

    val pythonPath: String get() = userConfig.pythonPath?.takeIf(String::isNotBlank) ?: "python3"
    val sam3dGcodeDir: String? get() = userConfig.sam3dGcodeDir?.takeIf(String::isNotBlank)
    val dicomFolderPath: String? get() = userConfig.dicomFolderPath?.takeIf(String::isNotBlank)
    val outputFolderPath: String? get() = userConfig.outputFolderPath?.takeIf(String::isNotBlank)
    val maxCachedBitmaps: Int get() = userConfig.maxCachedBitmaps ?: MAX_CACHED_BITMAPS

    /** sam3d.py `-s` slice count (§ task 6). Defaults to the engine's 120; set `slices` in config.json. */
    val slices: Int get() = userConfig.slices?.takeIf { it > 0 } ?: PipelineDefaults.SLICES

    /** Persisted window size (§ task 1); null until first saved. */
    val windowWidth: Int? get() = userConfig.windowWidth?.takeIf { it > 0 }
    val windowHeight: Int? get() = userConfig.windowHeight?.takeIf { it > 0 }

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
