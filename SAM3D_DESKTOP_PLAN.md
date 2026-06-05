# SAM3D Desktop Application — Comprehensive Implementation Plan (v2)

> **Document purpose:** Single source of truth for building the Kotlin / Compose-for-Desktop
> application that wraps the SAM3D-GCODE Python pipeline.  Any AI agent or developer reading this
> document should treat it as the authoritative specification.
>
> **v2 changes from v1 (summary):**
> - Integration strategy changed from Flask wrapper → **CLI subprocess** (zero Python changes required).
> - Module layout changed from `:core` + `:desktop` → **single `:composeApp`** with
>   `commonMain`/`jvmMain` source-set discipline (mobile refactor deferred).
> - Annotation JSON schema corrected to match what the Python pipeline actually reads.
> - Coordinate-frame math is now fully specified with a worked example.
> - Pixel windowing changed to match Python's min-max normalisation.
> - Coronal/sagittal rendering now keeps the full padded cube in memory (Python does this too).
> - Slices default corrected to 120 (was 32, a copy-paste error).
> - Flask endpoint table removed; replaced by CLI invocation contract.
> - Cancellation and "Start Over" semantics are now coherent.
> - Progress tracking uses stdout parsing (no Python changes needed).
> - SAM checkpoint verification added to Start screen.
> - Keyboard shortcuts moved from Phase 6 → Phase 4.
> - Per-OS config paths, logging, test deps, and fixtures are now specified.

---

## 1. Project Overview

### 1.1 What We Are Building
A cross-platform desktop application (macOS, Windows, Linux) that lets a non-technical clinician
run the full SAM3D-GCODE pipeline end-to-end through a graphical wizard — without ever touching a
terminal.  The pipeline converts a DICOM CT folder into a variable-density G-code file ready for
3D printing.

The Python codebase lives in a **separate sibling directory** (`../SAM3D-GCODE/` relative to this
repo).  Its exact path on disk is user-configured in `AppConfig`; the desktop app discovers it at
runtime.

### 1.2 The Full Pipeline (end-to-end flow)

```
DICOM folder
     │
     ▼
[Step 1 – Start]      User picks DICOM folder, output folder, and SAM3D-GCODE directory.
                       App verifies the Python binary and SAM checkpoint exist.
     │
     ▼
[Step 2 – Prompting]  User draws green (positive) and red (negative) polylines on DICOM
                       slices using the Compose Canvas viewer. Kotlin writes ONE file —
                       tempdir/points.json — in the exact format that sam3d.py reads.
     │
     ▼
[Steps 3-5 – Processing]
                       Kotlin spawns:
                         python sam3d.py \
                           --reslice 0 --reprompt 0 \
                           -p <dicomPath> -o <outputDir> \
                           -r ico -s 120 -v 1 \
                           --checkpoint checkpoints/sam_vit_h_4b8939.pth \
                           --datatype dcm
                       from the SAM3D-GCODE working directory.
                       Progress is shown by parsing stdout / tqdm lines.
                       Three sequential screens (Inference → Point Cloud → G-code) auto-advance
                       based on stdout markers.
     │
     ▼
[Step 5 – Done]       Output .gcode path is shown with a "Reveal in Finder/Explorer" button.
```

### 1.3 What We Are NOT Building (v1)
- We do **not** rewrite, wrap, or modify any file inside `SAM3D-GCODE/`.
- We do **not** build a 3D point-cloud viewer (delegated to Python/Open3D).
- We do **not** build a G-code preview renderer.
- We do **not** build a Flask HTTP layer — the app talks to Python via subprocess only.
- We do **not** build mobile (Android/iOS) in v1.  The single `:composeApp` module uses
  `commonMain` for platform-neutral code so a mobile source set can be added later with a
  bounded refactor (move `jvmMain` logic into `commonMain` + add `iosMain`/`androidMain`).

---

## 2. Absolute Non-Negotiables

| # | Constraint | Rationale |
|---|-----------|-----------|
| 1 | **Never touch `SAM3D-GCODE/` source files** | The Python engine is the research product. |
| 2 | **All long-running work runs off the main thread** | Compose Desktop's `Dispatchers.Main` dispatches to the Swing EDT via `kotlinx-coroutines-swing`. Blocking it freezes the UI. All I/O, DICOM decoding, subprocess management, and stdout parsing must use coroutines on `Dispatchers.IO` or `Dispatchers.Default`. |
| 3 | **`commonMain` must contain zero `java.*` / JVM-only APIs** | `java.io.File`, `ProcessBuilder`, `BufferedImage` live only in `jvmMain`. `commonMain` uses Kotlin stdlib + Compose Multiplatform types so it can host a mobile source set in future. |
| 4 | **Annotation JSON must exactly match what `sam3d.py` feeds to `scale_transform.parse_prompts`** | See §9 for the canonical schema. Any drift = silent inference failure. |
| 5 | **No global mutable state** | All wizard state flows from a single `WizardViewModel` as `StateFlow<WizardState>`. No singletons. |
| 6 | **The `sam3d.py` subprocess lifecycle is owned by the desktop app** | On app exit (including force-quit), the subprocess is destroyed via `Process.destroyForcibly()`. Register a JVM shutdown hook in `main.kt`. |
| 7 | **DICOM pixel data is decoded off-thread and cached** | Decoding a full series takes seconds. Results are cached in an LRU cache keyed on `(axis, sliceIndex)`. The UI renders a loading shimmer until the bitmap is ready. |
| 8 | **The DICOM viewer matches Python's normalisation** | Python's `utils.load3dmatrix` normalises to 0-255 via global min-max (`(pixel − min) / (max − min) × 255`). The Kotlin viewer must apply the same normalisation so the user annotates the image exactly as Python sees it. HU/windowing is v2. |
| 9 | **The full padded cube is held in memory during the Prompting step** | Coronal and sagittal slices require re-slicing across all axial images. For a 512³ cube this is ≈128 MB — acceptable. Never load more than one series at a time. |

---

## 3. Technology Stack

| Layer | Choice | Justification |
|-------|--------|---------------|
| Language | Kotlin 2.3.20 | Type safety, coroutines, multiplatform |
| UI | Compose Multiplatform 1.10.3 (JVM target) | Skia GPU rendering; single codebase path to mobile |
| State management | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` 2.10.0 | Same MVI pattern as Android; works via KMP |
| JSON | `kotlinx.serialization-json` 1.8.x | KMP-native; used only for `points.json` serialisation |
| DICOM parsing | dcm4che 5.31.0 (JVM) | Industry-standard; handles pixel data, multi-frame, transfer syntaxes |
| Coroutines | `kotlinx-coroutines-swing` 1.10.2 | Provides `Dispatchers.Main` on Swing EDT |
| Python integration | `ProcessBuilder` + stdout parsing | Zero Python changes; subprocess per pipeline run |
| Build | Gradle Kotlin DSL + Compose Desktop Gradle plugin | Already in scaffold |
| Packaging | `nativeDistributions` | `.dmg` / `.msi` / `.deb` without bundling Node or Python |
| Logging | `slf4j-api` + `slf4j-simple` | Lightweight; Python stdout → per-run log file |

> **Why no Ktor?** There is no HTTP layer. The Python pipeline is invoked as a subprocess.
> `kotlinx.serialization` is used only to read and write `points.json`.

> **Version note:** Verify `dcm4che 5.31.0` and `lifecycle-viewmodel-compose 2.10.0` exist in
> Maven Central before starting Phase 1. Pin exact versions in `libs.versions.toml`; do not use
> `+` or `x` wildcards.

---

## 4. Module Architecture (single `:composeApp`)

The project uses **one Gradle module** — the existing `:composeApp` — with two source sets:

```
composeApp/src/
├── commonMain/kotlin/edu/upenn/sam3d/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── DicomSeries.kt         ← data class, Kotlin stdlib types only
│   │   │   ├── SliceAnnotation.kt
│   │   │   ├── PipelineProgress.kt
│   │   │   ├── PipelineStage.kt       ← enum
│   │   │   ├── WizardStep.kt          ← enum
│   │   │   └── Axis.kt                ← enum (AXIS_0, AXIS_1, AXIS_2)
│   │   ├── repository/
│   │   │   ├── DicomRepository.kt     ← interface (returns ImageBitmap — CM type)
│   │   │   └── PipelineRepository.kt  ← interface
│   │   └── usecase/
│   │       ├── LoadDicomUseCase.kt
│   │       └── SaveAnnotationsUseCase.kt
│   └── state/
│       ├── WizardState.kt
│       ├── WizardIntent.kt
│       └── WizardViewModel.kt
│
└── jvmMain/kotlin/edu/upenn/sam3d/
    ├── main.kt                         ← application { } entry point
    ├── AppConfig.kt                    ← paths, defaults, stdout markers
    ├── dicom/
    │   ├── Dcm4cheLoader.kt            ← implements DicomRepository
    │   └── DicomBitmapCache.kt         ← LRU cache: (Axis, sliceIndex) → ImageBitmap
    ├── process/
    │   ├── PythonProcessManager.kt     ← spawns / kills sam3d.py subprocess
    │   └── StdoutProgressParser.kt     ← stdout line → PipelineProgress?
    └── ui/
        ├── App.kt
        ├── theme/AppTheme.kt
        ├── wizard/
        │   ├── WizardShell.kt
        │   ├── StartScreen.kt
        │   ├── PromptingScreen.kt
        │   ├── ProcessingScreen.kt     ← shared screen for Steps 3-5 (auto-advances)
        │   └── DoneScreen.kt
        ├── canvas/
        │   ├── DicomCanvas.kt
        │   └── AnnotationOverlay.kt
        └── components/
            ├── StageProgressBar.kt
            ├── CheckpointDownloadBar.kt
            ├── FilePicker.kt
            └── WindowingControls.kt
```

### 4.1 Source-set discipline rules

| Allowed in `commonMain` | Forbidden in `commonMain` |
|------------------------|--------------------------|
| Kotlin stdlib | `java.*` (except via Kotlin stdlib `expect`/`actual`) |
| Compose Multiplatform types (`ImageBitmap`, `Offset`, `Canvas`) | `ProcessBuilder`, `java.io.File` |
| `kotlinx.coroutines` (not `-swing`) | `java.awt.*`, `javax.*` |
| `kotlinx.serialization` | dcm4che |
| `androidx.lifecycle` KMP | `slf4j` (JVM-only) |

---

## 5. Wizard Flow & Screen Specifications

### 5.1 Shell Layout
Two-column layout:
- **Left (240 dp):** NavigationRail with 5 labeled steps. Completed steps show a checkmark.
  Future steps are disabled. Current step is highlighted.
- **Right (fill):** Content area that swaps between wizard screens.
- **Top bar:** App name left, Python verification status right (green ✓ = ready, red ✗ = not
  configured).
- **Bottom bar:** "Back" (left), "Next / Run" (right). "Next" is disabled until prerequisites
  are met.

### 5.2 Step 1 — Start

**Goal:** Collect paths, verify the Python environment, verify the SAM checkpoint.

**UI elements:**
- SAM3D-GCODE directory picker (where `sam3d.py` lives)
- DICOM folder picker
- Output folder picker (defaults to `~/Documents/SAM3D-Output/`)
- Python binary path (auto-detected from `AppConfig`; editable)
- Checkpoint status row: green if `checkpoints/sam_vit_h_4b8939.pth` exists inside the
  SAM3D-GCODE dir; if missing, shows a "Download checkpoint (2.5 GB)" button
- "Next" disabled until: DICOM folder selected, SAM3D-GCODE dir valid, Python binary verified,
  checkpoint present

**Under the hood:**
1. When SAM3D-GCODE dir or Python path changes, run `python --version` as a short subprocess to
   verify the binary works. Emit `PythonStatus.VERIFIED` or `PythonStatus.ERROR(stderr)`.
2. Check `<sam3dGcodeDir>/checkpoints/sam_vit_h_4b8939.pth` with `Files.exists`.
3. If checkpoint missing and user clicks "Download", spawn:
   ```
   <pythonExe> <sam3dGcodeDir>/download_checkpoint.py
   ```
   Stream stdout to `CheckpointDownloadBar` (parses tqdm percentage lines).
4. `LoadDicomUseCase` starts loading DICOM metadata in the background when the DICOM folder is
   selected, so Step 2 has it ready immediately.

**Pipeline config (hidden from user, in `AppConfig`):**
```kotlin
object PipelineDefaults {
    const val ROTATIONS  = "ico"
    const val SLICES     = 120   // matches sam3d.py default
    const val SAM_VERSION = 1
    const val CHECKPOINT = "checkpoints/sam_vit_h_4b8939.pth"
    const val DATATYPE   = "dcm"
}
```

### 5.3 Step 2 — Prompting (DICOM Annotation)

**Goal:** Let the user draw green (positive) and red (negative) polylines on DICOM slices.
Write a single `tempdir/points.json` in the exact format that `sam3d.py` feeds to
`scale_transform.parse_prompts`. See §9 for the schema.

**UI Layout:**
```
┌─────────────────────────────────────────────────────┐
│  [Axis 0] [Axis 1] [Axis 2]    Slice: 45 / 512     │  ← axis toggle + slice label
│  (Typical CT: Axis 2 = axial, 0 = sagittal,        │
│   1 = coronal — assumes canonical orientation)      │
│  ┌────────────────────────────────────────────────┐ │
│  │              DICOM CANVAS                      │ │
│  │     (min-max normalised grayscale slice)       │ │
│  │     + green polylines overlay                  │ │
│  │     + red polylines overlay                    │ │
│  └────────────────────────────────────────────────┘ │
│  [Slice slider ─────────────────────────────────]   │
│                                                      │
│  Drawing mode: [● Positive (Green)] [○ Negative (Red)]│
│  [New Line]  [Delete Last Point]  [Clear This Slice] │
│  Keyboard: A toggle ± | W new pos line | S new neg line│
│            D delete point | ←→ slice ±1 | 0/1/2 axis │
│  Annotations: 3 slices annotated                    │
└─────────────────────────────────────────────────────┘
```

**DicomCanvas implementation:**
- Backed by the **padded cube** (see §8 — the full S×S×S volume is in memory).
- Pixel values are the result of Python-equivalent min-max normalisation (see §8.1 for formula).
- `Canvas(modifier = Modifier.fillMaxSize())` with letterbox scaling (preserves aspect ratio;
  equal black bars on two sides if slice is not square).
- Bitmap: `drawImage(bitmap, dstOffset, dstSize)` inside `Canvas {}` — Skia-accelerated.
- Polylines: `drawLine` / `drawCircle` in the display rect coordinate space.

**Pointer events — implementation pattern:**
Use `awaitPointerEventScope` (not `detectDragGestures` + `detectTapGestures`, which conflict):
```kotlin
Modifier.pointerInput(activePolylineKey) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            when {
                change.pressed && !change.previousPressed -> onPointerDown(change.position)
                change.pressed  -> onPointerMove(change.position)
                !change.pressed && change.previousPressed -> onPointerUp()
            }
            change.consume()
        }
    }
}
```

**Coordinate system — see §8 for the full worked example.**
Internal annotation coordinates are stored in **padded-cube voxel space** — no normalisation to
0.0-1.0. Convert display-rect pixel coords to cube voxel coords on every pointer event.

**Annotation state (per axis, per slice):**
```kotlin
// in commonMain/domain/model/SliceAnnotation.kt
data class SliceAnnotation(
    val axis: Axis,               // AXIS_0, AXIS_1, AXIS_2
    val sliceIndex: Int,          // index into the padded cube along this axis
    val positivePolylines: List<List<IntArray>>,   // each IntArray is [x, y, z] in cube voxels
    val negativePolylines: List<List<IntArray>>
)
```

> **Coordinate convention:** A point on Axis 2, slice 45 is stored as `[x_voxel, y_voxel, 45]`.
> On Axis 0, slice 45: `[45, y_voxel, z_voxel]`. See §8 for the derivation.

**"Next" enabled when:** ≥1 positive polyline on ≥1 slice.

**On "Next":**
1. `SaveAnnotationsUseCase` merges all `SliceAnnotation` objects into the single
   `tempdir/points.json` format (§9) and writes to `<sam3dGcodeDir>/tempdir/points.json`.
   `tempdir/` is created by Kotlin at this point; any previous tempdir is deleted first.
2. Transition to Step 3.

### 5.4 Step 3 — Inference (auto-advances)

On transition from Step 2, `PythonProcessManager.start()` is called. It spawns `sam3d.py` and
begins reading stdout.

**UI:**
```
┌─────────────────────────────────────────────────────┐
│  Stage 1/5: Loading DICOM volume        [████░░░░]  │
│  Elapsed: 00:00:12                                   │
│                                                      │
│                              [Cancel]                │
└─────────────────────────────────────────────────────┘
```

- Each `StdoutProgressParser` emission drives the progress bar.
- "Back" is disabled while the subprocess is running.
- "Cancel" calls `PythonProcessManager.cancel()` → `Process.destroyForcibly()` → deletes tempdir
  → resets `WizardState` to `START` step.

### 5.5 Step 4 — Point Cloud (auto-advances)

Same `ProcessingScreen` composable, different stage label. Auto-advances when stdout emits the
point-cloud completion marker (see §7).

### 5.6 Step 5 — G-code (auto-advances to Done)

Same `ProcessingScreen`. When `sam3d.py` exits with code 0, transition to `DoneScreen`.

### 5.7 Done Screen

```
┌─────────────────────────────────────────────────────┐
│  ✓  G-code generated successfully!                  │
│                                                      │
│  Output file:                                        │
│  /Users/…/SAM3D-Output/output.gcode                 │
│                                                      │
│  [  Reveal in Finder  ]    [ Start Over ]           │
│                                                      │
│  Processing summary:                                 │
│  • Total time: 4 min 12 sec                         │
│  • Slices annotated: 6                              │
└─────────────────────────────────────────────────────┘
```

- "Reveal in Finder/Explorer/Files" is OS-specific (see §6.4).
- "Start Over" → `PythonProcessManager.reset()` (subprocess already exited) → delete tempdir →
  reset `WizardState` to initial → navigate to Step 1.

---

## 6. Core Components — Detailed Specifications

### 6.1 PythonProcessManager (jvmMain)

```kotlin
class PythonProcessManager(
    private val pythonExe: Path,       // e.g., conda env python binary
    private val sam3dScript: Path,     // <sam3dGcodeDir>/sam3d.py
    private val workingDir: Path,      // <sam3dGcodeDir> — MUST be the SAM3D-GCODE root
    private val parser: StdoutProgressParser
) {
    private var process: Process? = null
    private val _progress = MutableStateFlow<PipelineProgress?>(null)
    val progress: StateFlow<PipelineProgress?> = _progress.asStateFlow()

    // Returns a Job; completes when sam3d.py exits (success or error)
    fun start(
        dicomPath: Path,
        outputDir: Path,
        config: PipelineDefaults = PipelineDefaults
    ): Job

    fun cancel()            // destroyForcibly(); resets _progress
    fun isRunning(): Boolean
}
```

**Invocation inside `start()`:**
```kotlin
val cmd = listOf(
    pythonExe.toString(), sam3dScript.toString(),
    "--reslice", "0",        // do NOT wipe tempdir — Kotlin already wrote points.json
    "--reprompt", "0",       // skip the Tk annotation GUI
    "-p", dicomPath.toString(),
    "-o", outputDir.toString(),
    "-r", config.ROTATIONS,
    "-s", config.SLICES.toString(),
    "-v", config.SAM_VERSION.toString(),
    "--checkpoint", config.CHECKPOINT,
    "--datatype", config.DATATYPE
)
val pb = ProcessBuilder(cmd)
    .directory(workingDir.toFile())         // CRITICAL: relative paths in sam3d.py need this
    .redirectErrorStream(true)              // merge stderr into stdout
process = pb.start()
```

**Stdout reading loop (on `Dispatchers.IO`):**
```kotlin
process!!.inputStream.bufferedReader().use { reader ->
    reader.lines().forEach { line ->
        logger.debug("sam3d> {}", line)
        parser.parseLine(line)?.let { progress -> _progress.value = progress }
    }
}
val exitCode = process!!.waitFor()
if (exitCode != 0) _progress.value = PipelineProgress(PipelineStage.ERROR, exitCode)
```

**Stdout is always written to a log file** in the user data directory:
```
<userDataDir>/SAM3D/logs/sam3d-<ISO_TIMESTAMP>.log
```
Even if the UI crashes, the full Python output is preserved for debugging.

**JVM shutdown hook (in `main.kt`):**
```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    pythonProcessManager.cancel()
})
```

**Python binary discovery (in `AppConfig`):**
1. Check `userConfig.pythonPath`.
2. Try `which python3` / `where python3` as a fallback.
3. If all fail, `Start` screen shows an error row asking the user to enter the path manually.

### 6.2 StdoutProgressParser (jvmMain)

Maps stdout lines to `PipelineProgress` objects.

```kotlin
data class PipelineProgress(
    val stage: PipelineStage,
    val stagePercentage: Float = 0f,   // 0.0–1.0; from tqdm if parseable, else 0
    val elapsedSeconds: Long = 0L,
    val outputPath: String? = null     // non-null only at COMPLETE
)

enum class PipelineStage(val label: String) {
    LOADING_DICOM("Loading DICOM volume"),
    PREPARING_SLICES("Preparing slice views"),
    RUNNING_INFERENCE("Running SAM inference"),
    BUILDING_POINT_CLOUD("Building point cloud"),
    GENERATING_GCODE("Generating G-code"),
    COMPLETE("Complete"),
    ERROR("Error")
}
```

**Marker table** (verify by running `python sam3d.py ... 2>&1 | tee dry_run.log` and annotating
each print statement; commit `dry_run.log` to the test resources folder):

| stdout substring | → Stage |
|-----------------|---------|
| `"image loaded"` | `PREPARING_SLICES` |
| `"Making prompt slices"` | `PREPARING_SLICES` |
| `"transforms made"` (or after tqdm for slices) | `RUNNING_INFERENCE` |
| `"point cloud"` (case-insensitive) | `BUILDING_POINT_CLOUD` |
| `"gcode"` or `"G-code"` (case-insensitive) | `GENERATING_GCODE` |
| tqdm line `"NNN/NNN"` | parse percentage for current stage |

> **Brittleness note:** If `sam3d.py` changes its print statements, progress display breaks but
> correctness does not. Document the markers in `AppConfig.ProgressMarkers` as a named map so
> updating them is a one-line change.

**tqdm percentage parsing:**
tqdm emits lines like `Making prompt slices: 42%|████  | 5/12 [00:15<00:21,  3s/it]`.
Regex: `(\d+)%\|\S*\s*\|\s*(\d+)/(\d+)`.  Extract `current/total` for fine-grained progress.

### 6.3 Dcm4cheLoader (jvmMain)

```kotlin
class Dcm4cheLoader : DicomRepository {
    override suspend fun loadSeries(folderPath: String): DicomSeries
    override suspend fun loadSliceBitmap(series: DicomSeries, axis: Axis, index: Int): ImageBitmap?
}
```

**`DicomSeries` data class (commonMain):**
```kotlin
data class DicomSeries(
    val folderPath: String,
    val cubeSize: Int,          // S — the side length of the padded cube
    val rawShape: Triple<Int, Int, Int>,  // H × W × N before padding
    // Axis 2 corresponds to original DICOM slice direction
)
```

**Loading sequence:**
1. `loadSeries`: scan `.dcm` files, sort by `ImagePositionPatient[2]` (or `SliceLocation`).
   If no valid position tag exists, sort by filename.  Load pixel arrays, global-min-max
   normalise (matching `utils.load3dmatrix`), stack into `H×W×N` volume, pad to `S×S×S`
   (`padtocube`).  Store the padded cube in memory as a `ByteArray`.
2. `loadSliceBitmap`: slice the in-memory cube along `axis` at `index`, convert to grayscale
   `ImageBitmap`.

**Min-max normalisation formula (matches `utils.py:28`):**
```kotlin
fun normaliseVolume(rawPixels: FloatArray): ByteArray {
    val min = rawPixels.min()
    val max = rawPixels.max()
    val range = (max - min).coerceAtLeast(1f)
    return ByteArray(rawPixels.size) { i ->
        ((rawPixels[i] - min) / range * 255f).toInt().coerceIn(0, 255).toByte()
    }
}
```

> **v2 note:** This intentionally discards Hounsfield units. `RescaleSlope` / `RescaleIntercept`
> and HU-windowing will be added in v2 when the pipeline migrates to proper HU normalisation
> on the Python side.

**`padtocube` implementation (matches `utils.py:41-51`):**
```kotlin
fun padToCube(volume: Array3D<Byte>): Array3D<Byte> {
    val s = maxOf(volume.dimH, volume.dimW, volume.dimN)
    // symmetric zero-padding on each axis
    ...
}
```

**Bitmap caching:**
- Key: `Pair(axis, sliceIndex)`.
- Max size: `AppConfig.MAX_CACHED_SLICES` (default 256; the full cube is in memory as `ByteArray`,
  so caching decoded `ImageBitmap`s avoids repeated pixel-format conversion).
- Use `LinkedHashMap` with `accessOrder = true` for LRU eviction.
- Pre-fetch: `LaunchedEffect(sliceIndex)` triggers decoding of ±3 slices in the background.

**Compressed DICOM note:** dcm4che handles common uncompressed transfer syntaxes automatically.
JPEG2000-compressed DICOMs require `dcm4che-imageio` + a JPEG2000 ImageIO plugin on the
classpath. v1 targets uncompressed DICOMs (which covers the `00000304_points` test data).
Add a runtime check and a clear error message if an unsupported transfer syntax is encountered.

### 6.4 OS-specific utilities (jvmMain)

```kotlin
object OsUtils {
    fun revealInFileBrowser(path: Path) = when {
        isMac()     -> Runtime.getRuntime().exec(arrayOf("open", "-R", path.toString()))
        isWindows() -> Runtime.getRuntime().exec(arrayOf("explorer", "/select,${path}"))
        else        -> Runtime.getRuntime().exec(arrayOf("xdg-open", path.parent.toString()))
    }

    fun userDataDir(): Path = when {
        isMac()     -> Path(System.getProperty("user.home"), "Library", "Application Support", "SAM3D")
        isWindows() -> Path(System.getenv("APPDATA"), "SAM3D")
        else        -> Path(System.getenv("XDG_CONFIG_HOME")
                            ?: "${System.getProperty("user.home")}/.config", "sam3d")
    }
}
```

### 6.5 WizardViewModel (commonMain)

```kotlin
data class WizardState(
    val currentStep: WizardStep = WizardStep.START,
    val sam3dGcodeDir: String? = null,
    val dicomFolderPath: String? = null,
    val outputFolderPath: String? = null,
    val dicomSeries: DicomSeries? = null,
    val annotations: List<SliceAnnotation> = emptyList(),
    val pythonStatus: PythonStatus = PythonStatus.UNCHECKED,
    val checkpointExists: Boolean = false,
    val pipelineProgress: PipelineProgress? = null,
    val outputGcodePath: String? = null,
    val error: PipelineError? = null
)

sealed class PipelineError {
    data class Network(val cause: Throwable) : PipelineError()
    data class Server(val code: Int, val body: String) : PipelineError()
    data class Parse(val cause: Throwable) : PipelineError()
    object Cancelled : PipelineError()
    data class Unknown(val cause: Throwable) : PipelineError()
}

enum class PythonStatus { UNCHECKED, CHECKING, VERIFIED, ERROR }
enum class WizardStep { START, PROMPTING, PROCESSING, DONE }
```

```kotlin
sealed class WizardIntent {
    data class SetSam3dGcodeDir(val path: String) : WizardIntent()
    data class SetDicomFolder(val path: String) : WizardIntent()
    data class SetOutputFolder(val path: String) : WizardIntent()
    object DownloadCheckpoint : WizardIntent()
    object ProceedToPrompting : WizardIntent()
    data class AddPolylinePoint(
        val axis: Axis, val sliceIndex: Int, val x: Int, val y: Int, val mode: DrawingMode
    ) : WizardIntent()
    object EndPolyline : WizardIntent()
    data class ClearSlice(val axis: Axis, val sliceIndex: Int) : WizardIntent()
    object RunPipeline : WizardIntent()   // Step 2 → 3: writes points.json, spawns process
    object CancelPipeline : WizardIntent()
    object StartOver : WizardIntent()
}
```

The ViewModel exposes `StateFlow<WizardState>` and a `fun handle(intent: WizardIntent)` entry
point.  This is the **MVI** pattern — unidirectional data flow, no two-way bindings.

**Back-navigation rules (enforced in `WizardViewModel`):**

| From step | To step | Allowed? | Side effect |
|-----------|---------|---------|------------|
| PROMPTING | START | Yes | Clears `dicomSeries` if different folder selected |
| PROCESSING | PROMPTING | No | `WizardIntent.CancelPipeline` instead |
| DONE | PROMPTING | No | Use `StartOver` |
| DONE | START | Yes (via StartOver) | Full state reset |

---

## 7. Python CLI Invocation Contract

This replaces the Flask endpoint table from v1. These are the requirements the Kotlin app imposes
on the CLI invocation of `sam3d.py`.

### 7.1 Working directory

**Must be `<sam3dGcodeDir>/`** — the root of the SAM3D-GCODE repo.  `sam3d.py` uses relative
paths for `tempdir`, `checkpoints/`, and `outputs/`.  Setting the wrong working directory is the
single most common deployment error.

### 7.2 Command

```bash
<pythonExe> sam3d.py \
  --reslice 0 \
  --reprompt 0 \
  -p <absolute-path-to-dicom-folder> \
  -o <absolute-path-to-output-dir> \
  -r ico \
  -s 120 \
  -v 1 \
  --checkpoint checkpoints/sam_vit_h_4b8939.pth \
  --datatype dcm
```

| Flag | Value | Why |
|------|-------|-----|
| `--reslice 0` | skip | Kotlin already created and wrote `tempdir/points.json`; passing `1` would wipe it |
| `--reprompt 0` | skip | Kotlin performed the annotation; passing `1` would launch the Tk GUI |
| `-r ico` | icosahedron rotations | 12 projection angles; good quality / speed trade-off |
| `-s 120` | 120 slices | Matches `sam3d.py` default; provides good coverage |

### 7.3 Tempdir contract

- Kotlin **creates** `<sam3dGcodeDir>/tempdir/` at the start of each run (deleting any existing
  tempdir first).
- Kotlin **writes** `tempdir/points.json` before invoking the subprocess.
- `sam3d.py` with `--reslice 0` will **not wipe** the tempdir; it will add `slice_NN.png` files
  alongside `points.json` (the rotated slice views used internally).
- Kotlin **deletes** the entire tempdir on `StartOver` or after a cancelled run.

### 7.4 Expected stdout structure

Run `python sam3d.py <args> 2>&1 | tee dry_run.log` on the test data once.  Annotate the log
file to identify stage-transition markers.  Commit the annotated log as
`composeApp/src/jvmTest/resources/fixtures/dry_run_annotated.log`.

Known markers (verify on first dry run):

| stdout line contains | Stage |
|---------------------|-------|
| `"image loaded"` | Inference about to start |
| `"Making prompt slices"` (tqdm header) | Preparing rotated views |
| `"point cloud"` / `"pointcloud"` | Building point cloud |
| `".gcode"` in output line | G-code written |

### 7.5 Exit codes

| Code | Meaning | UI action |
|------|---------|-----------|
| 0 | Success | Transition to Done screen, show output path |
| 1 | Python exception | Show error dialog with last 20 log lines |
| −1 / SIGTERM | Cancelled by user | Already handled by `CancelPipeline` intent |

The output `.gcode` path is determined by watching stdout for a line that contains `.gcode`.  If
not found, scan `<outputDir>` for the most recently modified `.gcode` file after subprocess exit.

---

## 8. Coordinate Frames

This section is mandatory reading before implementing `SaveAnnotationsUseCase` or `DicomCanvas`.
Getting the coordinate transform wrong produces silent inference failures.

### 8.1 The three frames

| Frame | Description | Range |
|-------|-------------|-------|
| **Display pixels** | The rendered canvas in dp (Compose) | `0..canvasWidth`, `0..canvasHeight` |
| **Display rect** | Letterboxed area inside the canvas (preserves aspect ratio) | Subset of display pixels |
| **Cube voxels** | Position in the `S×S×S` padded cube | `0..S-1` on each axis |

```
Display pixels
      │  letterbox transform (preserve aspect ratio, centre image)
      ▼
Display rect   ←─── this is what the bitmap occupies
      │  scale: displayRect.width / S  (for axis-2 slice)
      ▼
Cube voxels (x, y)  ←─── stored in points.json + slice index embedded for z
```

### 8.2 Letterbox transform

The `DicomCanvas` renders the `S×S` bitmap centred inside a `canvasWidth × canvasHeight` area:

```kotlin
val scale = minOf(canvasWidth / S.toFloat(), canvasHeight / S.toFloat())
val displayW = S * scale
val displayH = S * scale
val offsetX = (canvasWidth - displayW) / 2f
val offsetY = (canvasHeight - displayH) / 2f
// DisplayRect: (offsetX, offsetY, displayW, displayH)
```

### 8.3 Pointer → cube voxel conversion (per axis)

```kotlin
fun displayToVoxel(
    pointerX: Float, pointerY: Float,
    displayRect: Rect,
    cubeSize: Int,
    axis: Axis,
    sliceIndex: Int
): IntArray {
    val voxelX = ((pointerX - displayRect.left) / displayRect.width * cubeSize)
                   .toInt().coerceIn(0, cubeSize - 1)
    val voxelY = ((pointerY - displayRect.top)  / displayRect.height * cubeSize)
                   .toInt().coerceIn(0, cubeSize - 1)
    return when (axis) {
        Axis.AXIS_0 -> intArrayOf(sliceIndex, voxelX, voxelY)
        Axis.AXIS_1 -> intArrayOf(voxelX, sliceIndex, voxelY)
        Axis.AXIS_2 -> intArrayOf(voxelY, voxelX, sliceIndex)   // voxelY first — see note below
    }
}
```

**The stored point is the cube ARRAY INDEX `[d0, d1, d2]` of the clicked voxel.**
`scale_transform.parse_prompts` consumes the point positionally (`cube[d0][d1][d2]`) with **no**
axis-specific handling, so the order must be the array index of exactly the voxel under the cursor.
The per-axis order is dictated by `Dcm4cheLoader.loadSliceBitmap`'s on-screen orientation:

| Axis | Fixed dim | screen-x → | screen-y → | Stored `[d0,d1,d2]` |
|------|-----------|------------|------------|---------------------|
| AXIS_0 | H (dim0) | w (dim1) | n (dim2) | `[slice, voxelX, voxelY]` |
| AXIS_1 | W (dim1) | h (dim0) | n (dim2) | `[voxelX, slice, voxelY]` |
| AXIS_2 | N (dim2) | w (dim1) | h (dim0) | `[voxelY, voxelX, slice]` |

> **AXIS_2 swaps voxelX/voxelY** because its bitmap shows screen-x = w (dim1) and screen-y = h
> (dim0). Storing `[voxelX, voxelY, slice]` there transposes the prompt across the cube diagonal,
> so the engine segments the *mirrored* location. This matches the engine's own `reprompting3d.py`
> `add_point` (which stores `(voxelY, voxelX, slice)` for axis 2) and was confirmed end-to-end with
> the SAM pipeline on a real asymmetric mark — see `docs/axis2_verification/`.

### 8.4 Worked example

Assume the DICOM series is 512 × 512 pixels × 300 slices.
- `rawShape = (512, 512, 300)` → `cubeSize S = 512` (largest dim).
- Padding adds (0, 0, 106) voxels symmetrically on the Z axis.
- Canvas is 800 × 600 dp; `scale = min(800/512, 600/512) = 1.1718…`; `displayW = displayH = 512 × 1.1718 = 600`; `offsetX = 100`, `offsetY = 0`.
- User taps at display pixel `(250, 180)` on Axis 2 (axial), slice 45.
- `voxelX = (250 − 100) / 600 × 512 = 128`, `voxelY = (180 − 0) / 600 × 512 = 153`.
- Point stored: `[153, 128, 45]` — i.e. `[voxelY, voxelX, slice]` (the cube array index of the
  clicked voxel; see the AXIS_2 note in §8.3). Storing `[128, 153, 45]` would transpose the prompt.

`scale_transform.parse_prompts` then adds `padding_constant = int((√3 − 1)/2 × 512/2) ≈ 93` to
each coordinate before further processing.  **Kotlin does not apply this addition** — it is
Python's responsibility.

### 8.5 Rendering voxel coords back onto canvas

```kotlin
fun voxelToDisplay(point: IntArray, axis: Axis, sliceIndex: Int, displayRect: Rect, cubeSize: Int): Offset? {
    // Inverse of §8.3: recover (vx = voxelX = screen-x, vy = voxelY = screen-y) from the cube index.
    val (vx, vy) = when (axis) {
        Axis.AXIS_0 -> if (point[0] != sliceIndex) return null else Pair(point[1], point[2])
        Axis.AXIS_1 -> if (point[1] != sliceIndex) return null else Pair(point[0], point[2])
        Axis.AXIS_2 -> if (point[2] != sliceIndex) return null else Pair(point[1], point[0])
    }
    return Offset(
        displayRect.left + vx.toFloat() / cubeSize * displayRect.width,
        displayRect.top  + vy.toFloat() / cubeSize * displayRect.height
    )
}
```

---

## 9. Annotation JSON Format

### 9.1 Schema (canonical)

A single file: `<sam3dGcodeDir>/tempdir/points.json`

```json
{
  "positive": [
    [[x0, y0, z0], [x1, y1, z1], ...],
    [[x0, y0, z0], ...]
  ],
  "negative": [
    [[x0, y0, z0], ...]
  ]
}
```

- Top-level keys: **`"positive"`** and **`"negative"`** (not `"pos"`/`"neg"`).
- Each key maps to a list of polylines.
- Each polyline is a list of points.
- Each point is `[x, y, z]` — all three coordinates in **padded-cube voxel space** — with the
  slice index embedded at the active-axis position (see §8.3).
- Points from all axes and all slices are merged into the two top-level lists.

### 9.2 Kotlin data class

```kotlin
// in commonMain/domain/model/
@Serializable
data class AnnotationFile(
    val positive: List<List<List<Int>>>,
    val negative: List<List<List<Int>>>
)
```

`SaveAnnotationsUseCase` converts `List<SliceAnnotation>` → `AnnotationFile` → JSON string →
file write (on `Dispatchers.IO`).

### 9.3 Fixture

A known-good `points.json` from a real run of the Tk annotation tool (`reprompting3d.py`) must
be committed to:
```
composeApp/src/jvmTest/resources/fixtures/points.fixture.json
```
alongside a comment-file documenting the cube size and DICOM series used to generate it.

**Integration test requirement:** `SaveAnnotationsUseCaseTest` must assert that serialising a
hand-crafted `List<SliceAnnotation>` produces byte-identical output to the corresponding
fixture rows.  This test catches any key-name or structure drift before runtime.

### 9.4 Verification checklist (before Phase 4)

Read these files in the SAM3D-GCODE repo; do not guess:
- `reprompting3d.py` → `save_points()` (actual file write).
- `scale_transform.py` → `parse_prompts()` (actual file read).
- `sam3d.py:101` → call site.

Confirm: file name, directory, key names, coordinate order, integer vs float types.

---

## 10. Performance Guidelines

### 10.1 Compose Desktop rules

| Rule | Implementation |
|------|---------------|
| Never block `Dispatchers.Main` | DICOM decode, file I/O, subprocess on `Dispatchers.IO`. CPU-bound pixel ops on `Dispatchers.Default`. |
| Minimise recomposition scope | Each UI section in its own `@Composable`. State read at the lowest possible level. |
| Stable types for Canvas | `@Immutable` on `SliceAnnotation` and `DicomSeries`. |
| Cache DICOM bitmaps | LRU cache. Never decode the same slice twice. |
| Pre-fetch adjacent slices | `LaunchedEffect` pre-decodes ±3 slices when user is on slice N. |
| Hardware-accelerated Canvas | `drawImage(bitmap)` inside `Canvas {}` — Skia GPU. |
| Avoid allocation in draw loop | Pre-compute polyline `FloatArray`s before entering `drawContent {}`. |

### 10.2 Memory management

- The **padded cube** (`S×S×S × 1 byte`) is held in memory as a `ByteArray` for the duration
  of the Prompting step. For S = 512: 128 MB. For S = 768 (large CT): 432 MB — approaching the
  JVM heap configured in `gradle.properties` (`-Xmx3072M`). Monitor via Task Manager / Activity
  Monitor during testing; increase `Xmx` if needed.
- The `ByteArray` cube is freed (GC-eligible) once the user proceeds to the Processing step.
- `ImageBitmap` LRU cache: `AppConfig.MAX_CACHED_BITMAPS = 256` (256 × 512×512×4 bytes ≈ 256 MB).
  Adjust based on available RAM during QA.
- Axis switching does **not** flush the bitmap cache (the cube stays the same; only the slice
  dimension changes).

### 10.3 Coroutine scopes

| Scope | Used for |
|-------|---------|
| `viewModelScope` | All ViewModel-initiated work (DICOM load, annotation save, subprocess lifecycle, progress collection) |
| `rememberCoroutineScope()` | Composable-local operations (file picker dialog) |
| Singleton `CoroutineScope(SupervisorJob() + Dispatchers.IO)` | `PythonProcessManager` — survives ViewModel recreation |

---

## 11. Build & Packaging

### 11.1 `libs.versions.toml` additions

```toml
[versions]
# (existing)
androidx-lifecycle      = "2.10.0"
composeMultiplatform    = "1.10.3"
kotlin                  = "2.3.20"
kotlinx-coroutines      = "1.10.2"
material3               = "1.10.0-alpha05"
# (new)
dcm4che                 = "5.31.0"
kotlinx-serialization   = "1.8.0"   # verify latest at build start
slf4j                   = "2.0.13"
junit                   = "4.13.2"
turbine                 = "1.2.0"   # Flow testing
mockk                   = "1.13.12"

[libraries]
# (existing…)
# (new)
dcm4che-core            = { module = "org.dcm4che:dcm4che-core",     version.ref = "dcm4che" }
dcm4che-imageio         = { module = "org.dcm4che:dcm4che-imageio",  version.ref = "dcm4che" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
slf4j-api               = { module = "org.slf4j:slf4j-api",          version.ref = "slf4j" }
slf4j-simple            = { module = "org.slf4j:slf4j-simple",       version.ref = "slf4j" }
turbine                 = { module = "app.cash.turbine:turbine",      version.ref = "turbine" }
mockk                   = { module = "io.mockk:mockk",                version.ref = "mockk" }
kotlin-test-junit       = { module = "org.jetbrains.kotlin:kotlin-test-junit", version.ref = "kotlin" }

[plugins]
# (existing…)
kotlinSerialization     = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

### 11.2 `composeApp/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)   // ← new
}

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.turbine)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.dcm4che.core)
            implementation(libs.dcm4che.imageio)
            implementation(libs.slf4j.api)
            implementation(libs.slf4j.simple)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit)
            implementation(libs.mockk)
        }
    }
}

compose.desktop {
    application {
        mainClass = "edu.upenn.sam3d.MainKt"   // lives in jvmMain/kotlin/edu/upenn/sam3d/main.kt
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SAM3D"
            packageVersion = "1.0.0"
            macOS { iconFile.set(project.file("src/jvmMain/composeResources/drawable/icon.icns")) }
            windows { iconFile.set(project.file("src/jvmMain/composeResources/drawable/icon.ico")) }
            linux   { iconFile.set(project.file("src/jvmMain/composeResources/drawable/icon.png")) }
        }
        // Bundle a default config template; NOT user config (user config lives in userDataDir)
        fromFiles(project.fileTree("src/jvmMain/resources") { include("config.default.json") })
    }
}
```

### 11.3 User config vs bundled template

- **Bundled** (`src/jvmMain/resources/config.default.json`): default values only — no paths.
- **User config** (`<userDataDir>/SAM3D/config.json`): created on first launch by copying the
  template; user edits it to set `pythonPath`, `sam3dGcodeDir`, `maxCachedBitmaps`.
- `AppConfig` reads the user config at startup; falls back to defaults for any missing key.

### 11.4 What NOT to bundle

Python, conda environments, the SAM checkpoint (2.5 GB), and the SAM3D-GCODE repo are **not**
bundled. The user configures these paths in `config.json`.

---

## 12. Phased Implementation Order

### Phase 1 — Foundation

1. Update `libs.versions.toml` with all new dependencies; verify all artefacts resolve.
2. Apply `kotlinSerialization` plugin.
3. Implement `WizardState`, `WizardIntent`, `WizardViewModel` skeleton (MVI, no business logic).
4. Implement `PythonProcessManager` — verify binary, parse stdout with a stub parser.
5. Implement `StdoutProgressParser` — write unit tests against known log lines.
6. Write unit tests for `WizardViewModel` state transitions (use `Turbine` for `StateFlow`).
7. **Validation:** `WizardViewModel` transitions through `START → PROCESSING → DONE` with a mocked
   `PythonProcessManager` that emits fake `PipelineProgress` events.

### Phase 2 — DICOM Loading + Padded Cube

1. Implement `Dcm4cheLoader.loadSeries()` — load, min-max normalise, `padToCube`, store `ByteArray`.
2. Implement `Dcm4cheLoader.loadSliceBitmap()` — slice cube, convert to `ImageBitmap`.
3. Implement `DicomBitmapCache`.
4. Write unit test: load `00000304_points` DICOM series → assert `cubeSize = S`, render axial
   slice 0, confirm non-zero pixels.
5. **Validation:** Load the test series; render Axis 2 slice 150; bone structure visible.

### Phase 3 — Wizard Shell + Start Screen

1. Build `WizardShell` (NavigationRail + content area).
2. Build `StartScreen` with folder pickers, SAM3D-GCODE dir picker, python verification row,
   checkpoint status row.
3. Wire `WizardViewModel` intents.
4. **Validation:** All pickers open, python verified via `python --version`, checkpoint check works.

### Phase 4 — Prompting Screen

1. Build `DicomCanvas` composable (bitmap rendering, letterbox, `awaitPointerEventScope` input).
2. Build `AnnotationOverlay` (polyline rendering).
3. Build axis switcher, slice slider.
4. Implement keyboard shortcuts: `A` toggle ±, `W` new positive, `S` new negative, `D` delete
   last, left/right arrow slice ±1, `0`/`1`/`2` axis switch.
5. Build `SaveAnnotationsUseCase` — writes `points.json` per §9.
6. Write integration test: annotate 2 slices programmatically → assert JSON output matches fixture.
7. **Validation:** Load DICOM; draw 3 positive polylines on Axis 2 slice 20; switch to Axis 0;
   draw 1 polyline on slice 10; click "Run Pipeline" → confirm `tempdir/points.json` written with
   correct keys and coordinate values (check manually against §8.4 worked example).

### Phase 5 — Processing Screens + End-to-End

1. Build `ProcessingScreen` (shared composable for Steps 3-5, driven by `PipelineProgress` flow).
2. Build `StageProgressBar` (determinate per-stage, indeterminate fallback).
3. Build `DoneScreen` with `OsUtils.revealInFileBrowser`.
4. Wire `PythonProcessManager.start()` to `WizardIntent.RunPipeline`.
5. Do a dry run with real Python (`python sam3d.py ...`) and annotate `dry_run.log`.  Update
   `AppConfig.ProgressMarkers` from annotations.  Commit the annotated log.
6. **Validation:** Full end-to-end on `00000304_points` DICOM.  G-code file produced; "Reveal"
   button opens Finder/Explorer at the output file.

### Phase 6 — Polish

1. Error handling: Python crash (non-zero exit) → show last 20 log lines in an error dialog.
2. Loading shimmer while DICOM slice is decoded.
3. Window size persistence in user config.
4. Checkpoint download progress bar (`CheckpointDownloadBar`).
5. `BackendStatusBadge` replaced by a "Python ready" static indicator on the Start screen.
6. macOS notarization + codesigning notes (defer to release prep checklist).
7. CI skeleton: GitHub Actions matrix (macOS/Windows/Linux) that builds a distribution on tag.

### Phase 7 (post-v1) — Persistent Session / Crash Recovery

- Save annotations to a draft in `<userDataDir>/SAM3D/sessions/<timestamp>/` on every "Next"
  click.  On startup, if an incomplete session exists, offer to resume.

---

## 13. Directory Structure (target)

```
sam3d/
├── composeApp/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/edu/upenn/sam3d/
│       │   ├── domain/
│       │   │   ├── model/
│       │   │   │   ├── DicomSeries.kt
│       │   │   │   ├── SliceAnnotation.kt
│       │   │   │   ├── AnnotationFile.kt        ← @Serializable, matches points.json
│       │   │   │   ├── PipelineProgress.kt
│       │   │   │   ├── PipelineStage.kt
│       │   │   │   ├── WizardStep.kt
│       │   │   │   └── Axis.kt
│       │   │   ├── repository/
│       │   │   │   └── DicomRepository.kt       ← interface
│       │   │   └── usecase/
│       │   │       ├── LoadDicomUseCase.kt
│       │   │       └── SaveAnnotationsUseCase.kt
│       │   └── state/
│       │       ├── WizardState.kt
│       │       ├── WizardIntent.kt
│       │       └── WizardViewModel.kt
│       │
│       ├── jvmMain/
│       │   ├── kotlin/edu/upenn/sam3d/
│       │   │   ├── main.kt
│       │   │   ├── AppConfig.kt
│       │   │   ├── OsUtils.kt
│       │   │   ├── dicom/
│       │   │   │   ├── Dcm4cheLoader.kt
│       │   │   │   └── DicomBitmapCache.kt
│       │   │   ├── process/
│       │   │   │   ├── PythonProcessManager.kt
│       │   │   │   └── StdoutProgressParser.kt
│       │   │   └── ui/
│       │   │       ├── App.kt
│       │   │       ├── theme/AppTheme.kt
│       │   │       ├── wizard/
│       │   │       │   ├── WizardShell.kt
│       │   │       │   ├── StartScreen.kt
│       │   │       │   ├── PromptingScreen.kt
│       │   │       │   ├── ProcessingScreen.kt
│       │   │       │   └── DoneScreen.kt
│       │   │       ├── canvas/
│       │   │       │   ├── DicomCanvas.kt
│       │   │       │   └── AnnotationOverlay.kt
│       │   │       └── components/
│       │   │           ├── StageProgressBar.kt
│       │   │           ├── CheckpointDownloadBar.kt
│       │   │           ├── FilePicker.kt
│       │   │           └── WindowingControls.kt
│       │   ├── composeResources/drawable/
│       │   │   └── ic_sam3d.xml
│       │   └── resources/
│       │       └── config.default.json
│       │
│       └── jvmTest/
│           ├── kotlin/edu/upenn/sam3d/
│           │   ├── SaveAnnotationsUseCaseTest.kt
│           │   ├── StdoutProgressParserTest.kt
│           │   ├── Dcm4cheLoaderTest.kt
│           │   └── WizardViewModelTest.kt
│           └── resources/fixtures/
│               ├── points.fixture.json          ← from a real reprompting3d.py run
│               └── dry_run_annotated.log        ← sam3d.py stdout with stage annotations
│
├── build.gradle.kts    (root)
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── SAM3D_DESKTOP_PLAN.md
```

---

## 14. Key Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| `points.json` schema drifts from Python | `AnnotationFile` is a single `@Serializable` data class. Integration test asserts byte-equality vs fixture. |
| Progress markers change when Python team edits `sam3d.py` | Markers live in `AppConfig.ProgressMarkers` (one map to edit). Progress display breaks before correctness does; monitored by running the dry-run log test on each update. |
| `sam3d.py` invoked with wrong working directory | `PythonProcessManager` asserts `workingDir.resolve("sam3d.py").exists()` before spawning. Surfaced as a setup error on the Start screen. |
| SAM checkpoint missing | Start screen checks existence; offers download via `download_checkpoint.py` with a progress bar. |
| Large DICOM OOM (cube > 2 GB) | `Dcm4cheLoader.loadSeries` checks `cubeSize^3 < AppConfig.MAX_CUBE_BYTES`; if exceeded, shows an error asking the user to contact support. Increase JVM `-Xmx` in `gradle.properties` if needed. |
| Python process hangs forever | `PythonProcessManager.start()` sets a `withTimeout(AppConfig.PIPELINE_TIMEOUT_MS)`. On timeout, `cancel()` is called and the error screen is shown. |
| macOS Gatekeeper blocks spawned Python | Use absolute path to the conda env binary. Document that users may need to allow it in System Settings → Privacy & Security on first launch. |
| `xdg-open` fails on some Linux WMs | `OsUtils.revealInFileBrowser` falls back to opening the parent directory if the direct file reveal fails silently. |
| SAM3D-GCODE Python stdout format changes | `StdoutProgressParser` is tested against the committed `dry_run_annotated.log`. A failing parser test is the signal to update markers. |

**PHI note:** All DICOM processing is on-device. No patient data leaves the machine. The app
does not transmit DICOMs or annotations to any network endpoint.

---

## 15. Back-Navigation Rules

| Current step | Trying to go to | Allowed | Mechanism |
|-------------|----------------|---------|-----------|
| PROMPTING | START | Yes | NavigationRail click; annotations preserved unless DICOM path changes |
| PROCESSING | PROMPTING | **No** | Only `CancelPipeline` is offered (kills subprocess, resets to START) |
| PROCESSING | START | **No** | Same — cancel first |
| DONE | START | Yes | `StartOver` intent; full state reset + tempdir deletion |
| DONE | PROMPTING | **No** | `StartOver` then re-annotate |

Going from DONE back to START does **not** kill a subprocess (it has already exited). It cleans
up tempdir and resets `WizardState` to initial values.

---

## 16. Summary of All Architecture Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Integration strategy | **CLI subprocess** (`sam3d.py` via `ProcessBuilder`) | Zero Python changes; aligns with the CLI-first design of the research pipeline |
| Module structure | **Single `:composeApp`** with `commonMain`/`jvmMain` source sets | Ships desktop fast; bounded mobile refactor later |
| Annotation handoff | Write `tempdir/points.json` directly; invoke subprocess with `--reprompt 0` | Bypasses the Tk GUI; no HTTP layer needed |
| Progress tracking | Parse `sam3d.py` stdout / tqdm lines | No Python changes; markers decoupled into `AppConfig` |
| Cancellation | `Process.destroyForcibly()` | Immediate; reliable; subprocess owns no persistent state |
| Start Over | Kill subprocess (if any) → delete tempdir → reset `WizardState` | Clean slate; no stale Python state |
| Pixel normalisation | Global min-max (matches `utils.load3dmatrix`) | User sees exactly what Python sees; HU-windowing → v2 |
| Volume memory | Full padded cube (`S³` `ByteArray`) held during Prompting | Required for coronal/sagittal slicing; consistent with Python |
| DICOM parsing library | dcm4che 5.31.0 (JVM) | Industry-standard; handles uncompressed transfer syntaxes |
| Coordinate frame | Padded-cube voxel space; slice index at axis position | Matches `reprompting3d.py` convention exactly (§8) |
| JSON schema | `{"positive": [...], "negative": [...]}` single file | Matches `scale_transform.parse_prompts` exactly (§9) |
| State management | MVI — `WizardViewModel` + `StateFlow<WizardState>` | Unidirectional; testable; no two-way bindings |
| JSON library | `kotlinx.serialization` | KMP-native; used only for `points.json` |
| Packaging | `nativeDistributions` `.dmg`/`.msi`/`.deb` | No Python or Node bundled |
| User config | Per-OS path (`~/Library/Application Support/SAM3D/` on mac) | OS-conventional; separate from bundled template |
| Logging | `slf4j-simple` for Kotlin; Python stdout → log file | Lightweight; both sides debuggable post-crash |
| Test stack | JUnit 4 + Turbine + MockK | Standard for KMP JVM targets |
