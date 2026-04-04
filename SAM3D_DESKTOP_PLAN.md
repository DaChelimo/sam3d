# SAM3D Desktop Application — Comprehensive Implementation Plan

> **Document purpose:** This is the single source of truth for building the Kotlin Multiplatform
> Compose-for-Desktop wrapper around the SAM3D-GCODE Python pipeline. Any AI agent or developer
> reading this document should treat it as the authoritative specification. Do **not** modify,
> rewrite, or delete anything inside the `SAM3D-GCODE/` repository. That codebase is the engine.
> This desktop app is the cockpit.

---

## 1. Project Overview

### 1.1 What We Are Building
A cross-platform desktop application (initially macOS + Windows + Linux) that lets a non-technical
clinician run the full SAM3D-GCODE pipeline end-to-end through a clean graphical wizard — without
ever touching a terminal. The pipeline converts DICOM medical images (e.g., CT scans of a femur)
into variable-density G-code files ready for 3D printing.

### 1.2 The Full Pipeline (end-to-end flow)
```
DICOM folder
     │
     ▼
[Step 1 – Start]      User picks DICOM folder & output folder. App spawns Python backend.
     │
     ▼
[Step 2 – Prompting]  User draws green (positive) and red (negative) polylines on DICOM slices
                       using the Compose Canvas DICOM viewer. Annotations are written to JSON
                       files in a `tempdir` in the exact format that reprompting3d.py reads.
     │
     ▼
[Step 3 – Inference]  App calls Flask /inference. Python runs SAM3D segmentation.
                       Progress shown via stage-based determinate progress bar.
     │
     ▼
[Step 4 – Point Cloud] App calls Flask /pointcloud. Python refines the 3D point cloud.
                        No 3D viewer on the Kotlin side — delegated entirely to Python.
                        Progress bar only.
     │
     ▼
[Step 5 – G-code]     App calls Flask /variable-density then /gcode. Python runs Otsu +
                        RegionBased + Voxels2GCode. Output .gcode file path shown to user
                        with a "Reveal in Finder / Explorer" button.
```

### 1.3 What We Are NOT Building
- We are **not** rewriting, wrapping, or modifying any file inside `SAM3D-GCODE/` — no edits to
  `sam3d.py`, `prompting.py`, `backend/app.py`, or any other Python file whatsoever.
- We are **not** building a 3D point-cloud viewer (delegated to Python/Open3D).
- We are **not** building a G-code preview renderer.
- We are **not** building mobile (Android/iOS) yet — but the module structure **must** accommodate
  it from day one.

---

## 2. Absolute Non-Negotiables

| # | Constraint | Rationale |
|---|-----------|-----------|
| 1 | **Never touch `SAM3D-GCODE/` source files** | The Python engine is the research product. Any breakage there has scientific consequences. The desktop app wraps it via HTTP only. |
| 2 | **All long-running work runs off the main thread** | Compose Desktop renders on the Swing EDT. Blocking it causes the UI to freeze. All I/O, HTTP calls, DICOM decoding, and subprocess management must use coroutines on `Dispatchers.IO`. |
| 3 | **`:core` module must contain zero JVM-specific APIs** | `java.io.File`, `ProcessBuilder`, `BufferedImage` must live only in `:desktop`. `:core` uses `expect/actual` or purely Kotlin stdlib/KMP APIs so the module can be consumed by future Android/iOS targets. |
| 4 | **Annotation JSON format must exactly match what `reprompting3d.py` / `scale_transform.py` parse** | Any schema drift = silent failure deep in the Python pipeline. Define one canonical data class in `:core` and write a single serializer. |
| 5 | **No global mutable state** | All wizard state flows downward from a single `WizardViewModel` as immutable `StateFlow`. No `companion object` singletons, no static fields. |
| 6 | **The Python process lifecycle is owned by the desktop app** | On app exit (even via force-quit / window close), the spawned Python process must be destroyed. Register a JVM shutdown hook. |
| 7 | **DICOM pixel data is decoded off-thread and cached** | Decoding a full DICOM series can take seconds. Results are cached in a `LruCache<Int, ImageBitmap>`. The UI renders a loading placeholder until the bitmap is ready. |

---

## 3. Technology Stack

| Layer | Choice | Justification |
|-------|--------|---------------|
| Language | Kotlin 2.x | Type safety, coroutines, multiplatform |
| UI | Compose Multiplatform for Desktop (JVM target) | Single codebase with Android/iOS path; hardware-accelerated Skia rendering |
| State management | `androidx.lifecycle:lifecycle-viewmodel-compose` | Same pattern as Android; works on desktop via KMP |
| HTTP client | **Ktor Client** (CIO engine) | KMP-compatible; async; no extra threads needed |
| JSON | `kotlinx.serialization` | KMP-native; fast; integrates with Ktor |
| DICOM parsing | **dcm4che 5.x** (JVM) | Industry-standard; parses pixel data, metadata, multi-frame |
| Coroutines | `kotlinx.coroutines-swing` | Provides `Dispatchers.Main` on Swing EDT for Compose Desktop |
| Build | Gradle Kotlin DSL + Compose Desktop Gradle plugin | Already in scaffold |
| Packaging | Compose Desktop `nativeDistributions` | `.dmg` / `.msi` / `.deb` without bundling Node |

> **Why not Ktor for DICOM?** DICOM parsing is a JVM-only concern and lives in `:desktop`. Ktor
> lives in `:core` as an interface / expect-actual pattern so mobile can substitute a different
> HTTP engine.

---

## 4. Module Architecture

```
sam3d/                          ← Gradle root project
├── :core                       ← Pure KMP module — NO JVM APIs
│   ├── domain/
│   │   ├── model/              ← Data classes (DicomSeries, Annotation, PipelineStage, etc.)
│   │   ├── repository/         ← Interfaces (DicomRepository, PipelineRepository)
│   │   └── usecase/            ← Business logic (LoadDicomUseCase, SaveAnnotationsUseCase, etc.)
│   ├── api/
│   │   ├── PipelineApiClient   ← Ktor HTTP calls to Flask (interface + impl)
│   │   └── dto/                ← Request/Response data classes with @Serializable
│   └── state/
│       ├── WizardState.kt      ← Sealed class for all wizard states
│       └── WizardViewModel.kt  ← Single shared ViewModel
│
├── :desktop                    ← JVM-only module
│   ├── main.kt                 ← application { } entry point
│   ├── dicom/
│   │   ├── Dcm4cheLoader.kt    ← Implements DicomRepository using dcm4che
│   │   └── DicomBitmapCache.kt ← LRU cache: slice index → ImageBitmap
│   ├── process/
│   │   └── PythonProcessManager.kt  ← Spawns/kills Flask backend
│   ├── ui/
│   │   ├── wizard/
│   │   │   ├── WizardShell.kt       ← NavigationRail + content area layout
│   │   │   ├── StartScreen.kt
│   │   │   ├── PromptingScreen.kt
│   │   │   ├── InferenceScreen.kt
│   │   │   ├── PointCloudScreen.kt
│   │   │   └── GCodeScreen.kt
│   │   ├── canvas/
│   │   │   ├── DicomCanvas.kt       ← Compose Canvas for rendering slices
│   │   │   └── AnnotationOverlay.kt ← Polyline drawing layer on top of DICOM
│   │   └── components/
│   │       ├── StageProgressBar.kt  ← Determinate multi-stage progress
│   │       ├── BackendStatusBadge.kt
│   │       └── FilePicker.kt        ← JVM AWT file chooser wrapped in coroutine
│   └── theme/
│       └── AppTheme.kt
│
└── build.gradle.kts (root)
```

### 4.1 Module Dependency Graph
```
:desktop  ──depends on──►  :core
                              │
                         (interfaces)
                              │
                         [Ktor, kotlinx.serialization, coroutines]
```

`:desktop` is allowed to use all JVM APIs. `:core` is forbidden from importing anything from
`java.*` beyond what Kotlin stdlib already provides via `expect/actual`.

---

## 5. Wizard Flow & Screen Specifications

### 5.1 Shell Layout
The outermost window uses a two-column layout:
- **Left column (240 dp):** NavigationRail with 5 labeled steps. Steps before the current step are
  marked complete (checkmark icon). Steps after are locked (disabled). Current step is highlighted.
- **Right column (fill):** Content area that swaps between screens based on current step.
- **Top bar:** App name left, `BackendStatusBadge` right (pulsing green dot = connected, red = not).
- **Bottom bar:** "Back" (left) and "Next / Run" (right) buttons. "Next" is disabled if the
  current step's prerequisites are not met.

### 5.2 Step 1 — Start

**Goal:** Collect inputs and confirm the backend is alive.

**UI elements:**
- DICOM folder picker (shows selected path in a read-only text field)
- Output folder picker (defaults to `~/Documents/SAM3D-Output/`)
- Backend status indicator with a "Retry" button if not connected
- "Next" button is **disabled** until: (a) a DICOM folder is selected, (b) backend is connected

**Under the hood:**
1. `PythonProcessManager.start()` is called when this screen is first shown (not on app launch).
2. A coroutine on `Dispatchers.IO` polls `GET /healthcheck` every 1 second for up to 30 seconds.
3. On success, `WizardViewModel` transitions `backendState` to `BackendState.Connected`.
4. `LoadDicomUseCase` loads DICOM metadata (slice count, dimensions, patient info) in the
   background so the Prompting screen has it ready immediately.

**Pipeline config (hidden from user, in `config.json` or hardcoded constants):**
```kotlin
object PipelineDefaults {
    const val ROTATIONS = "ico"
    const val SLICES = 32
    const val SAM_VERSION = 1
    const val CHECKPOINT = "checkpoints/sam_vit_h_4b8939.pth"
    const val DATATYPE = "dcm"
}
```

### 5.3 Step 2 — Prompting (DICOM Annotation)

This is the most complex screen. It is the heart of the desktop app.

**Goal:** Let the user draw green (positive) and red (negative) polylines on DICOM slices. Save
annotations to the `tempdir` in the exact JSON format that the Python pipeline reads.

**UI Layout (single-pane + axis switcher):**
```
┌─────────────────────────────────────────────────────┐
│  [Axial] [Coronal] [Sagittal]    Slice: 45 / 128   │  ← axis toggle + slice label
│                                                      │
│  ┌────────────────────────────────────────────────┐ │
│  │                                                │ │
│  │              DICOM CANVAS                      │ │  ← Compose Canvas, fills space
│  │     (gray-scale rendered DICOM slice)          │ │
│  │     + green polylines overlay                  │ │
│  │     + red polylines overlay                    │ │
│  │                                                │ │
│  └────────────────────────────────────────────────┘ │
│                                                      │
│  [Slice slider  ────────────────────────────────]   │  ← scrollable, also mouse-wheel
│                                                      │
│  Drawing mode: [● Positive (Green)] [○ Negative (Red)]  │
│  [New Line]  [Delete Last Point]  [Clear This Slice] │
│  Annotations: 3 slices annotated                     │
└─────────────────────────────────────────────────────┘
```

**DicomCanvas implementation:**
- Uses `Canvas(modifier = Modifier.fillMaxSize().pointerInput(...))`.
- The raw DICOM pixel array (Hounsfield units) is windowed (window center/width) and normalized
  to 0–255. This produces a grayscale `ImageBitmap`.
- Windowing defaults: `windowCenter = 400`, `windowWidth = 1500` (bone window) — user-adjustable
  via a small slider in a collapsed "Display" section.
- Bitmap rendering: `drawImage(bitmap, ...)` on the Compose `DrawScope`.
- Polyline rendering: `drawLine(...)` / `drawCircle(...)` over the bitmap.

**Pointer events:**
- `detectDragGestures` + `detectTapGestures` are combined via `pointerInput`.
- While dragging, append `(x, y)` to the current active polyline.
- On tap, add a single point.
- Store all coordinates as **normalized floats** (0.0–1.0) internally, convert to pixel-space for
  rendering and to (row, col, 0) format for JSON export.

**Annotation state (per axis, per slice):**
```kotlin
data class SliceAnnotation(
    val axisIndex: Int,      // 0=axial, 1=coronal, 2=sagittal
    val sliceIndex: Int,
    val positivePolylines: List<List<Pair<Float, Float>>>,  // normalized coords
    val negativePolylines: List<List<Pair<Float, Float>>>
)
```

**JSON export format** (must exactly match what `reprompting3d.py` writes/reads):
```json
{
  "pos": [[[row, col, 0], [row, col, 0], ...], ...],
  "neg": [[[row, col, 0], [row, col, 0], ...], ...]
}
```
One JSON file per slice view. File naming must match the convention used by `prompting.py` and
`scale_transform.py`. **Before implementing, read both files to confirm exact filename patterns.**

**"Next" is enabled when:** at least one positive polyline exists on at least one slice.

**On "Next":**
1. `SaveAnnotationsUseCase` writes all `SliceAnnotation` objects to the `tempdir` as JSON.
2. A POST to `GET /prompt` with the tempdir path confirms handoff.
3. Transition to Step 3.

### 5.4 Step 3 — Inference

**Goal:** Trigger SAM3D inference and show progress.

**UI:**
```
┌─────────────────────────────────────────────────────┐
│  Running segmentation inference…                     │
│                                                      │
│  Stage 1/4: Loading DICOM volume          [████░░]  │
│  Stage 2/4: Running SAM inference         [██░░░░]  │
│  Stage 3/4: Collecting point cloud        [░░░░░░]  │
│  Stage 4/4: Building voxel mask           [░░░░░░]  │
│                                                      │
│  Elapsed: 00:01:23                                   │
└─────────────────────────────────────────────────────┘
```

- Progress is driven by polling `GET /inference/status` (a new endpoint the Flask backend needs
  to expose — see §8.2 for the contract). The endpoint returns `{"stage": 2, "total_stages": 4,
  "stage_label": "Running SAM inference"}`.
- Poll interval: 2 seconds on `Dispatchers.IO`.
- "Back" is disabled during inference (cannot interrupt). "Cancel" button is visible.
- On cancel, the app sends `POST /inference/cancel` and kills the Python subprocess.

### 5.5 Step 4 — Point Cloud

**Goal:** Trigger point-cloud refinement. No 3D visualization in the Kotlin UI.

**UI:**
```
┌─────────────────────────────────────────────────────┐
│  Refining point cloud…                              │
│                                                      │
│  The segmentation mask is being converted to a 3D   │
│  point cloud and refined.                            │
│                                                      │
│  [████████████████░░░░]  68%                        │
│                                                      │
│  Elapsed: 00:00:45                                   │
└─────────────────────────────────────────────────────┘
```

- Triggers `POST /pointcloud` with hardcoded default parameters (downsample=1, outliers=1,
  n_neighbors=24 for ico rotation, radius=0.02, iterations=4).
- Polls `GET /pointcloud/status` for progress.

### 5.6 Step 5 — G-code Export

**Goal:** Trigger variable-density + G-code generation and surface the output file.

**UI:**
```
┌─────────────────────────────────────────────────────┐
│  ✓  G-code generated successfully!                  │
│                                                      │
│  Output file:                                        │
│  /Users/andrew/Documents/SAM3D-Output/output.gcode  │
│                                                      │
│  [  Reveal in Finder  ]    [ Start Over ]           │
│                                                      │
│  ─────────────────────────────────────────────────  │
│  Processing summary:                                 │
│  • Total time: 4 min 12 sec                         │
│  • Slices annotated: 6                              │
│  • Point cloud points: 84,219                       │
└─────────────────────────────────────────────────────┘
```

- `POST /variable-density` then `POST /gcode`.
- On success, display the output path and a "Reveal in Finder/Explorer" button
  (`Desktop.getDesktop().browseFileDirectory(file)`).
- "Start Over" resets the `WizardViewModel` to initial state and returns to Step 1.
  The Python process is **not** killed — it stays alive for the next run.

---

## 6. Core Components — Detailed Specifications

### 6.1 PythonProcessManager (`:desktop`)

```kotlin
class PythonProcessManager(
    private val pythonExe: Path,        // e.g., conda env python binary
    private val backendScript: Path,    // SAM3D-GCODE/backend/app.py
    private val workingDir: Path
) {
    private var process: Process? = null

    fun start(): Job  // returns coroutine Job that completes once /healthcheck succeeds
    fun stop()        // destroy process, called from JVM shutdown hook
    fun isRunning(): Boolean
}
```

**Implementation notes:**
- Use `ProcessBuilder` with `inheritIO()` suppressed — redirect stdout/stderr to a log file in the
  app's data directory so crashes are debuggable.
- Spawn on `Dispatchers.IO`.
- Register shutdown hook in `main()`:
  ```kotlin
  Runtime.getRuntime().addShutdownHook(Thread { pythonProcessManager.stop() })
  ```
- On first launch, if no Python executable is configured, show an error dialog pointing user to
  the README. Store the path in a `preferences.json` file in the OS user-data directory.

**Where to find the Python binary:**
- Check `AppConfig.pythonPath` (from `~/.config/sam3d/config.json`).
- If not set, try `conda run -n SAM3D_GCODE python` as a fallback.
- If all fail, show a one-time setup dialog.

### 6.2 Dcm4cheLoader (`:desktop`)

```kotlin
class Dcm4cheLoader : DicomRepository {
    override suspend fun loadSeries(folderPath: Path): DicomSeries
    override suspend fun loadSliceBitmap(series: DicomSeries, axis: Axis, index: Int): ImageBitmap
}
```

**`DicomSeries` data class (in `:core`):**
```kotlin
data class DicomSeries(
    val folderPath: String,
    val axialCount: Int,
    val coronalCount: Int,
    val sagittalCount: Int,
    val width: Int,
    val height: Int,
    val pixelSpacingMm: Pair<Double, Double>,
    val sliceThicknessMm: Double,
    val windowCenter: Int,
    val windowWidth: Int
)
```

**Bitmap caching:**
- Key: `Triple(axis, sliceIndex, windowPreset)`.
- Max cache size: 128 bitmaps (configurable). Each DICOM slice at 512×512 is ~1 MB as ARGB.
  128 slices = ~128 MB peak. Acceptable.
- Use a `LinkedHashMap`-backed LRU or `androidx.collection.LruCache`.
- Pre-fetch: when user is on slice N, pre-decode slices N+2 and N−2 in the background on
  `Dispatchers.Default`.

**Pixel windowing formula:**
```kotlin
fun windowHU(hu: Int, center: Int, width: Int): Int {
    val lo = center - width / 2
    val hi = center + width / 2
    return ((hu - lo).toFloat() / (hi - lo) * 255).toInt().coerceIn(0, 255)
}
```
Apply this per-pixel then pack into a grayscale `ImageBitmap` using `toArgb()`.

### 6.3 DicomCanvas + AnnotationOverlay (`:desktop/ui/canvas`)

These are two composables that layer on top of each other:

```kotlin
@Composable
fun DicomCanvas(
    bitmap: ImageBitmap?,            // null = show loading shimmer
    annotations: SliceAnnotation,
    drawingMode: DrawingMode,        // POSITIVE or NEGATIVE
    onPointerDown: (Offset) -> Unit,
    onPointerMove: (Offset) -> Unit,
    onPointerUp: () -> Unit,
    modifier: Modifier = Modifier
)
```

**Performance rules for DicomCanvas:**
- The `drawImage(bitmap)` call is inside `Canvas {}` — it is Skia-native and hardware accelerated.
  Do **not** wrap it in `Image()` composable (causes unnecessary recomposition).
- Polyline data is passed as stable `@Immutable` annotated classes to prevent spurious
  recomposition of the canvas when unrelated state changes.
- Use `remember { mutableStateOf<ImageBitmap?>(null) }` local to the composable and launch a
  `LaunchedEffect(sliceIndex, axis)` that fetches the bitmap from the cache/loader.
- Avoid `derivedStateOf` chains longer than 2 levels.

**Coordinate system:**
- All internal annotation coordinates are stored normalized (0.0–1.0) relative to canvas size.
- When rendering: multiply by `canvas.size.width` / `.height`.
- When exporting to JSON: multiply by the DICOM pixel dimensions (width × height) to get
  `(row, col, 0)` in pixel space.

### 6.4 PipelineApiClient (`:core`)

```kotlin
interface PipelineApiClient {
    suspend fun healthcheck(): Boolean
    suspend fun submitPrompt(tempDirPath: String): Result<Unit>
    suspend fun startInference(config: InferenceConfig): Result<Unit>
    suspend fun getInferenceStatus(): Result<PipelineStatus>
    suspend fun cancelInference(): Result<Unit>
    suspend fun startPointCloud(config: PointCloudConfig): Result<Unit>
    suspend fun getPointCloudStatus(): Result<PipelineStatus>
    suspend fun startVariableDensity(): Result<Unit>
    suspend fun getVariableDensityStatus(): Result<PipelineStatus>
}

data class PipelineStatus(
    val stage: Int,
    val totalStages: Int,
    val stageLabel: String,
    val elapsedSeconds: Long
)
```

Implement with Ktor CIO client. Use `Result<T>` wrapping so callers never throw.

### 6.5 WizardViewModel (`:core`)

```kotlin
data class WizardState(
    val currentStep: WizardStep = WizardStep.START,
    val dicomFolderPath: String? = null,
    val outputFolderPath: String? = null,
    val dicomSeries: DicomSeries? = null,
    val annotations: List<SliceAnnotation> = emptyList(),
    val backendStatus: BackendStatus = BackendStatus.DISCONNECTED,
    val inferenceProgress: PipelineStatus? = null,
    val pointCloudProgress: PipelineStatus? = null,
    val gcodeProgress: PipelineStatus? = null,
    val outputGcodePath: String? = null,
    val error: String? = null
)

enum class WizardStep { START, PROMPTING, INFERENCE, POINT_CLOUD, GCODE }
enum class BackendStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
```

The ViewModel exposes a single `StateFlow<WizardState>` and a set of `fun handle(intent: WizardIntent)` functions. This is the **MVI pattern** — unidirectional data flow.

```kotlin
sealed class WizardIntent {
    data class SelectDicomFolder(val path: String) : WizardIntent()
    data class SelectOutputFolder(val path: String) : WizardIntent()
    object StartBackend : WizardIntent()
    object ProceedToPrompting : WizardIntent()
    data class AddAnnotationPoint(val axis: Axis, val sliceIdx: Int, val point: Offset, val mode: DrawingMode) : WizardIntent()
    object NewPolyline : WizardIntent()
    object ProceedToInference : WizardIntent()
    object CancelOperation : WizardIntent()
    object StartOver : WizardIntent()
    // ... etc.
}
```

---

## 7. Flask Backend — Required Endpoint Contracts

The existing `backend/app.py` has stub endpoints. The Kotlin app will call these. The Python-side
developer (NOT us — we do not touch these files) must implement the following contracts.

> **We provide this contract as documentation only. We do not write Python code.**

| Endpoint | Method | Request | Response |
|----------|--------|---------|----------|
| `/healthcheck` | GET | — | `{"status": "ok"}` |
| `/prompt` | POST | `{"tempdir": "/abs/path/to/tempdir"}` | `{"ok": true}` |
| `/inference` | POST | `{"config": {...}}` | `{"ok": true}` |
| `/inference/status` | GET | — | `{"stage": 2, "total_stages": 4, "stage_label": "...", "elapsed_seconds": 83}` |
| `/inference/cancel` | POST | — | `{"ok": true}` |
| `/pointcloud` | POST | `{"downsample": 1, "n_neighbors": 24, "radius": 0.02, "iterations": 4}` | `{"ok": true}` |
| `/pointcloud/status` | GET | — | `{"stage": 1, "total_stages": 2, "stage_label": "...", "elapsed_seconds": 45}` |
| `/variable-density` | POST | — | `{"ok": true}` |
| `/variable-density/status` | GET | — | `{"stage": 1, "total_stages": 3, "stage_label": "...", "elapsed_seconds": 12}` |
| `/gcode/status` | GET | — | `{"stage": 1, "total_stages": 1, "stage_label": "...", "elapsed_seconds": 5, "output_path": "/abs/..."}` |

---

## 8. Performance Guidelines (Desktop-Specific)

### 8.1 Compose Desktop Performance Rules

| Rule | Implementation |
|------|---------------|
| Never block `Dispatchers.Main` | All I/O (DICOM decode, HTTP, file write) on `Dispatchers.IO`. All CPU work (pixel windowing) on `Dispatchers.Default`. |
| Minimize recomposition scope | Each UI section is in its own `@Composable` function. State is read at the lowest possible level. |
| Stable types for Canvas | `@Immutable` on `SliceAnnotation` and `DicomSeries`. Kotlin compiler will skip recomposition if reference equals. |
| Cache DICOM bitmaps | LRU cache. Never decode the same slice twice. |
| Pre-fetch adjacent slices | `LaunchedEffect` pre-decodes ±2 slices when user is on slice N. |
| Hardware-accelerated Canvas | Use `drawImage(bitmap)` inside `Canvas {}` — Skia GPU backend. Never convert to `BufferedImage` inside the UI layer. |
| Avoid allocation in draw loop | No `List` or `Pair` creation inside `drawContent { }`. Pre-compute polyline point arrays before draw. |
| Window is `singleInstance` | One window, one process. Avoid multiple Compose windows. |

### 8.2 Memory Management

- DICOM volumes can be 512×512×300 slices = 75 million pixels. **Never** load the full 3D volume
  into RAM in the Kotlin layer. Load one slice at a time on demand.
- The LRU cache limit of 128 bitmaps (~128 MB) is a soft ceiling. Expose a config key
  `MAX_CACHED_SLICES` in `AppConfig`.
- When the user changes axis, flush the cache for the previous axis to free memory.

### 8.3 Coroutine Scopes

| Scope | Used for |
|-------|---------|
| `viewModelScope` | All ViewModel-initiated operations (pipeline status polling, backend spawn) |
| `rememberCoroutineScope()` | Composable-local operations (file picker dialog) |
| Dedicated `CoroutineScope(SupervisorJob() + Dispatchers.IO)` | PythonProcessManager — survives ViewModel recreation |

---

## 9. KMP Desktop Best Practices

### 9.1 `expect`/`actual` for Platform Specifics
Place in `:core`:
```kotlin
expect fun openFilePicker(title: String, allowedExtensions: List<String>): String?
expect fun revealInFileBrowser(path: String)
expect fun getAppDataDirectory(): String
```

Implement in `:desktop` using JVM `JFileChooser` / `Desktop.getDesktop()` / system property.

### 9.2 Resource Loading
Put all assets (icons, fonts) under `composeApp/src/jvmMain/composeResources/`. Access with
`painterResource(Res.drawable.xxx)`.

### 9.3 Window Configuration
```kotlin
application {
    Window(
        onCloseRequest = {
            pythonProcessManager.stop()
            exitApplication()
        },
        title = "SAM3D",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
        resizable = true,
        undecorated = false
    ) {
        App(viewModel = wizardViewModel)
    }
}
```

### 9.4 Theme
Use Material 3 (`MaterialTheme`). Define a custom `ColorScheme` suited to a medical application:
dark, high-contrast, with a neutral gray base. Avoid overly colorful primary palettes that would
feel out of place in a clinical context. Full styling is deferred to a future iteration — for now
use Material 3 defaults.

---

## 10. Build & Packaging

### 10.1 `build.gradle.kts` (`:desktop` module additions)

```kotlin
kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.dcm4che:dcm4che-core:5.31.0")
            implementation("org.dcm4che:dcm4che-imageio:5.31.0")
            implementation("io.ktor:ktor-client-cio:2.3.x")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.x")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.x")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.x")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.x")
        }
    }
}

compose.desktop {
    application {
        mainClass = "edu.upenn.sam3d.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SAM3D"
            packageVersion = "1.0.0"
            macOS { iconFile.set(project.file("src/jvmMain/resources/icon.icns")) }
            windows { iconFile.set(project.file("src/jvmMain/resources/icon.ico")) }
        }
        // Include config.json in the distribution
        fromFiles(project.fileTree("src/jvmMain/resources") { include("config.json") })
    }
}
```

### 10.2 What NOT to Bundle
The Python backend is **not** bundled in the Kotlin distribution. The app discovers Python via
config. This is intentional — the Python environment (conda, 2.5 GB SAM checkpoint) is too large
to bundle and is already set up by the user.

---

## 11. Phased Implementation Order

Build in this exact order to ensure correctness and avoid integration surprises:

### Phase 1 — Foundation (Do first, no UI yet)
1. Set up `:core` and `:desktop` Gradle modules.
2. Implement `WizardState`, `WizardIntent`, `WizardViewModel` with MVI skeleton.
3. Implement `PythonProcessManager` — spawn, poll healthcheck, kill.
4. Implement `PipelineApiClient` with Ktor — all endpoints (can hit the stub Flask backend).
5. Write unit tests for `WizardViewModel` state transitions.
6. **Validation:** launch app → Python spawns → `/healthcheck` returns OK → `BackendStatus.CONNECTED`.

### Phase 2 — DICOM Loading
1. Add dcm4che dependency.
2. Implement `Dcm4cheLoader.loadSeries()` (metadata only).
3. Implement `Dcm4cheLoader.loadSliceBitmap()` with pixel windowing.
4. Implement `DicomBitmapCache` (LRU).
5. **Validation:** load the `00000304_points` DICOM series, render slice 0 to an `ImageBitmap`,
   verify the bone is visible.

### Phase 3 — Wizard Shell + Start Screen
1. Build `WizardShell` (NavigationRail + content area).
2. Build `StartScreen` with folder pickers and backend status badge.
3. Wire `WizardViewModel` intents to the UI.
4. **Validation:** full Start screen works, folder picker opens, backend status animates.

### Phase 4 — Prompting Screen (Core UI)
1. Build `DicomCanvas` composable with bitmap rendering.
2. Build `AnnotationOverlay` with pointer input for polyline drawing.
3. Build axis switcher, slice slider, mode toggle.
4. Build `SaveAnnotationsUseCase` — writes JSON to `tempdir`.
5. **Validation:** load DICOM, draw 3 positive polylines on slice 20, switch axis, draw on slice
   10, click "Next" → verify JSON files written to `tempdir`.

### Phase 5 — Inference, PointCloud, GCode Screens
1. Build `StageProgressBar` component.
2. Build `InferenceScreen` with polling loop.
3. Build `PointCloudScreen` (simpler — no interaction).
4. Build `GCodeScreen` with output path display and reveal button.
5. **Validation:** run full pipeline end-to-end (will be limited until Flask stubs are replaced with
   real implementations by the Python team).

### Phase 6 — Polish Pass
1. Error handling (network errors, DICOM parse errors, Python crash recovery).
2. Keyboard shortcuts (scroll wheel on DICOM canvas, A/D to switch axis).
3. Loading shimmer placeholder while DICOM slice is being decoded.
4. Window size persistence in preferences.

---

## 12. Directory Structure (Final Target)

```
sam3d/
├── core/
│   └── src/
│       └── commonMain/kotlin/edu/upenn/sam3d/core/
│           ├── domain/
│           │   ├── model/
│           │   │   ├── DicomSeries.kt
│           │   │   ├── SliceAnnotation.kt
│           │   │   ├── PipelineStatus.kt
│           │   │   └── WizardStep.kt
│           │   ├── repository/
│           │   │   ├── DicomRepository.kt      (interface)
│           │   │   └── PipelineRepository.kt   (interface)
│           │   └── usecase/
│           │       ├── LoadDicomUseCase.kt
│           │       └── SaveAnnotationsUseCase.kt
│           ├── api/
│           │   ├── PipelineApiClient.kt        (interface + Ktor impl)
│           │   └── dto/
│           │       ├── PromptRequest.kt
│           │       ├── InferenceConfig.kt
│           │       ├── PointCloudConfig.kt
│           │       └── PipelineStatusResponse.kt
│           └── state/
│               ├── WizardState.kt
│               ├── WizardIntent.kt
│               └── WizardViewModel.kt
│
├── desktop/
│   └── src/
│       └── jvmMain/
│           ├── kotlin/edu/upenn/sam3d/desktop/
│           │   ├── main.kt
│           │   ├── AppConfig.kt
│           │   ├── dicom/
│           │   │   ├── Dcm4cheLoader.kt
│           │   │   └── DicomBitmapCache.kt
│           │   ├── process/
│           │   │   └── PythonProcessManager.kt
│           │   └── ui/
│           │       ├── App.kt
│           │       ├── theme/
│           │       │   └── AppTheme.kt
│           │       ├── wizard/
│           │       │   ├── WizardShell.kt
│           │       │   ├── StartScreen.kt
│           │       │   ├── PromptingScreen.kt
│           │       │   ├── InferenceScreen.kt
│           │       │   ├── PointCloudScreen.kt
│           │       │   └── GCodeScreen.kt
│           │       ├── canvas/
│           │       │   ├── DicomCanvas.kt
│           │       │   └── AnnotationOverlay.kt
│           │       └── components/
│           │           ├── StageProgressBar.kt
│           │           ├── BackendStatusBadge.kt
│           │           ├── FilePicker.kt
│           │           └── WindowingControls.kt
│           └── composeResources/
│               └── drawable/
│                   └── ic_sam3d.xml
│
├── build.gradle.kts   (root)
├── settings.gradle.kts
└── SAM3D_DESKTOP_PLAN.md   (this file)
```

---

## 13. Key Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| `reprompting3d.py` JSON format changes | Define a `@Serializable` `AnnotationFileFormat` data class in `:core`. A single change there updates all serialization. Add an integration test that compares serialized output to a fixture. |
| Python process fails to start | 30-second timeout on healthcheck polling. If it fails, show a clear error dialog with the last 10 lines of the Python log file. |
| Large DICOM series OOM | LRU cache with 128 bitmaps cap. `WeakReference`-backed secondary cache for rarely accessed slices. |
| Flask stub endpoints → real endpoints have different schemas | All HTTP responses go through a single `PipelineApiClient` with typed DTOs. Schema mismatches fail fast at the DTO layer with a clear error. |
| Compose Desktop hot-reload disrupts in-progress pipeline | Disable hot-reload in production build. Keep `composeHotReload` only in debug builds. |
| macOS sandbox / Gatekeeper blocks spawned Python | Use `ProcessBuilder` with explicit absolute path to the conda env Python. Document that users may need to right-click to open on first launch. |

---

## 14. Annotation JSON Format Research Note

**Before implementing `SaveAnnotationsUseCase`, the developer MUST read:**
- `SAM3D-GCODE/reprompting3d.py` — to see exactly what JSON structure it reads.
- `SAM3D-GCODE/scale_transform.py` → `parse_prompts()` function — to understand how it traverses
  the tempdir to find per-slice files.
- `SAM3D-GCODE/prompting.py` → the `save_annotations()` or equivalent function — to see the
  exact filename convention.

Do NOT guess the format. The annotation step is the only place where the Kotlin app creates files
that the Python pipeline directly reads. Getting this wrong means silent failure at inference time.

---

## 15. Summary of All Architecture Decisions

| Decision | Choice |
|----------|--------|
| Integration strategy | HTTP/REST — Kotlin spawns Flask, calls endpoints |
| Annotation canvas | Full Compose Canvas with native DICOM rendering + polyline overlay |
| DICOM viewer layout | Single-pane with axis switcher + slice slider |
| DICOM parsing library | dcm4che 5.x (JVM) |
| Annotation handoff | Write JSON to disk → POST tempdir path to `/prompt` |
| Pipeline parameters | Sensible defaults hidden from user (in `AppConfig`) |
| Long-operation feedback | Stage-based determinate progress bar |
| 3D point cloud viewer | Delegated to Python; no Kotlin 3D viewer |
| Module structure | `:core` (KMP, no JVM APIs) + `:desktop` (JVM) from day 1 |
| Python process | Spawned and owned by the Kotlin app; killed on exit via JVM shutdown hook |
| State management | MVI — single `WizardViewModel` with `StateFlow<WizardState>` |
| HTTP client | Ktor CIO (KMP-compatible) |
| JSON | kotlinx.serialization |
| Packaging | Compose Desktop `nativeDistributions` (no Python bundled) |
