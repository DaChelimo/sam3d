package edu.upenn.sam3d.state

import edu.upenn.sam3d.domain.model.DicomSeries
import edu.upenn.sam3d.domain.model.PipelineProgress
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
    // sam3d.py `-s` count, chosen on Setup via the quality toggle (Draft=8 / Production=120).
    val slices: Int = 120,
    val pipelineProgress: PipelineProgress? = null,
    val outputGcodePath: String? = null,
    val error: PipelineError? = null
)
