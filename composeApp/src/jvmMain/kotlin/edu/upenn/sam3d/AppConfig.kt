package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.domain.model.QualityPreset
import edu.upenn.sam3d.domain.model.UserConfig
import edu.upenn.sam3d.engine.EngineStager
import java.nio.file.Path
import kotlin.io.path.exists

object AppConfig {
    const val MAX_CACHED_BITMAPS = 256
    const val MAX_CUBE_BYTES = 2L * 1024 * 1024 * 1024  // 2 GB hard limit

    /**
     * Free space *Set up environment* needs before it starts: ~0.1 GB for uv + a managed CPython,
     * ~3 GB for torch and the rest of the dependency tree, 2.4 GB for the SAM checkpoint, plus room
     * for uv's package cache and a Draft downsample. Checked up front so a full disk fails in two
     * seconds with a number the user can act on, instead of twenty minutes in with a pip traceback.
     */
    const val MIN_FREE_BYTES_FOR_SETUP = 8L * 1000 * 1000 * 1000  // 8 GB

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
        // Draft downsample target: the longest cube side after downsampling, in voxels. At 256³ the
        // engine's 6 rotated float64 copies total ≈2 GB of transient RAM during the transform — fine on
        // a 16 GB Mac and far from the full-res 21.6 GB blowup — while keeping enough detail to read the
        // anatomy. (Source of truth is QualityPreset.DRAFT.downsampleTargetMaxDim; this mirrors it.)
        const val DRAFT_TARGET_MAX_DIM = 256
    }

    /**
     * Per-user config (§11.3), loaded once from <userDataDir>/SAM3D/config.json (then the bundled
     * config.default.json, then these fallbacks). Replaces the previously hardcoded DevDefaults so
     * paths / the python env are edited in a file, not in source. The Start screen seeds empty
     * fields from these.
     */
    private val userConfig: UserConfig by lazy { ConfigLoader.load() }

    /**
     * The interpreter to run the engine with: the user's own if they configured one, else the venv
     * that *Set up environment* built, else a system fallback.
     *
     * The system fallback is platform-aware on purpose. It used to be `python3` everywhere, but on
     * Windows `python3` resolves to the Microsoft Store app-execution alias, which exits non-zero
     * without running anything — so a first-launch user saw the Python field go red and a "Verify the
     * Python environment to continue" blocker, moments after being told the app manages Python itself.
     */
    val pythonPath: String
        get() = userConfig.pythonPath?.takeIf(String::isNotBlank)
            ?: venvPythonPath.takeIf { Path.of(it).exists() }
            ?: defaultSystemPython()

    private fun defaultSystemPython(): String = if (OsUtils.isWindows()) "python" else "python3"

    /**
     * Resolves to the user-configured path if set; otherwise to the engine [EngineStager] finds —
     * the copy staged from the app's bundled resources for an installed build, or the repo's
     * `pipeline/` for a dev checkout. Resolved once per launch because staging touches the disk.
     *
     * Null only if the app ships without its engine resource *and* isn't running from a checkout, in
     * which case the Setup screen offers a manual folder picker (it must never dead-end).
     */
    val sam3dGcodeDir: String? by lazy {
        userConfig.sam3dGcodeDir?.takeIf(String::isNotBlank) ?: EngineStager.resolve()
    }

    val dicomFolderPath: String? get() = userConfig.dicomFolderPath?.takeIf(String::isNotBlank)
    val outputFolderPath: String? get() = userConfig.outputFolderPath?.takeIf(String::isNotBlank)
    val maxCachedBitmaps: Int get() = userConfig.maxCachedBitmaps ?: MAX_CACHED_BITMAPS

    /**
     * Where the one-click environment setup builds its Python venv: under the app data dir, NOT inside
     * `pipeline/` — so the vendored engine stays pristine and the venv survives a `git clean`/re-clone.
     */
    val venvDir: Path get() = OsUtils.userDataDir().resolve("venv")

    /** The interpreter inside [venvDir] (platform-aware). This is what a completed setup runs against. */
    val venvPythonPath: String get() = OsUtils.venvPython(venvDir).toString()

    /** UX hint that setup previously completed; the on-disk venv + checkpoint remain the source of truth. */
    val setupComplete: Boolean get() = userConfig.setupComplete == true

    /** sam3d.py `-s` slice count (§ task 6). Defaults to the engine's 120; set `slices` in config.json. */
    val slices: Int get() = userConfig.slices?.takeIf { it > 0 } ?: PipelineDefaults.SLICES

    /**
     * Last-chosen quality preset, restored on the Setup screen. Reads the `quality` key; for
     * configs written before this key existed, infers it from a low `slices` value so a prior Draft
     * choice (slices:8) still comes back as Draft.
     */
    val quality: QualityPreset
        get() = userConfig.quality?.let { runCatching { QualityPreset.valueOf(it.uppercase()) }.getOrNull() }
            ?: userConfig.slices?.let { if (it <= 20) QualityPreset.DRAFT else QualityPreset.PRODUCTION }
            ?: QualityPreset.PRODUCTION

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
