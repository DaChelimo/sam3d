package edu.upenn.sam3d.state

import edu.upenn.sam3d.domain.model.AppView
import edu.upenn.sam3d.domain.model.DicomSeries
import edu.upenn.sam3d.domain.model.PipelineProgress
import edu.upenn.sam3d.domain.model.QualityPreset
import edu.upenn.sam3d.domain.model.RunReport
import edu.upenn.sam3d.domain.model.SliceAnnotation
import edu.upenn.sam3d.domain.model.WizardStep

enum class PythonStatus { UNCHECKED, CHECKING, VERIFIED, ERROR }

enum class DrawingMode { POSITIVE, NEGATIVE }

sealed class PipelineError {
    data class Network(val cause: Throwable) : PipelineError()

    /**
     * Non-zero subprocess exit. [body] is the tail of sam3d.py's output; [logPath] points at the
     * full per-run log so the error dialog can offer "Open log"; [hint] is an optional plain-English
     * diagnosis inferred from the output (e.g. "checkpoint missing").
     */
    data class Server(
        val code: Int,
        val body: String,
        val logPath: String? = null,
        val hint: String? = null,
    ) : PipelineError()

    data class Parse(val cause: Throwable) : PipelineError()
    object Cancelled : PipelineError()
    data class Unknown(val cause: Throwable) : PipelineError()
}

data class WizardState(
    val currentStep: WizardStep = WizardStep.START,
    val sam3dGcodeDir: String? = null,
    val dicomFolderPath: String? = null,
    val outputFolderPath: String? = null,
    val pythonPath: String = "python3",
    val dicomSeries: DicomSeries? = null,
    val annotations: List<SliceAnnotation> = emptyList(),
    val pythonStatus: PythonStatus = PythonStatus.UNCHECKED,
    val checkpointExists: Boolean = false,
    val checkpointDownload: CheckpointDownload = CheckpointDownload.Idle,
    // The one-click environment setup (venv + deps + checkpoint) progress. Drives the Setup screen's
    // combined banner; on Succeeded it flips pythonStatus→VERIFIED and checkpointExists→true.
    val envSetup: EnvSetup = EnvSetup.Idle,
    // Setup quality choice (Draft vs Production). Drives BOTH the `-s` slice count and whether the
    // input is downsampled before annotation + the engine see it (§ pipeline-bottleneck fix).
    val quality: QualityPreset = QualityPreset.PRODUCTION,
    // sam3d.py `-s` count — kept in sync with `quality` (SetQuality), still settable on its own.
    val slices: Int = 120,
    // The DICOM folder actually fed to BOTH the annotation loader and the engine `-p`: the original
    // folder in Production, or the generated downsampled copy in Draft. Null until resolved.
    val effectiveDicomPath: String? = null,
    val dicomDownsampleStatus: DicomDownsampleStatus = DicomDownsampleStatus.Idle,
    val pipelineProgress: PipelineProgress? = null,
    val outputGcodePath: String? = null,
    val error: PipelineError? = null,
    // Top-level destination (wizard vs. Reports tab). Reports is a sibling of the whole wizard, so
    // switching to it leaves currentStep untouched and returning resumes exactly where you were.
    val appView: AppView = AppView.RUN,
    // The just-finished run's report, shown on the Done screen. Set on the terminal pipeline event.
    val lastRunReport: RunReport? = null,
    // All persisted runs, newest first — backs the Reports tab. Loaded lazily when it's opened.
    val reports: List<RunReport> = emptyList(),
)
