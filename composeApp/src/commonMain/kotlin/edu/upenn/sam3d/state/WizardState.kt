package edu.upenn.sam3d.state

import edu.upenn.sam3d.domain.model.DicomSeries
import edu.upenn.sam3d.domain.model.PipelineProgress
import edu.upenn.sam3d.domain.model.SliceAnnotation
import edu.upenn.sam3d.domain.model.WizardStep

enum class PythonStatus { UNCHECKED, CHECKING, VERIFIED, ERROR }

enum class DrawingMode { POSITIVE, NEGATIVE }

sealed class PipelineError {
    data class Network(val cause: Throwable) : PipelineError()
    data class Server(val code: Int, val body: String) : PipelineError()
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
    val pipelineProgress: PipelineProgress? = null,
    val outputGcodePath: String? = null,
    val error: PipelineError? = null
)
