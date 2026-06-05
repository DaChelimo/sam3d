package edu.upenn.sam3d.state

import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.domain.model.DicomSeries

sealed class WizardIntent {
    data class SetSam3dGcodeDir(val path: String) : WizardIntent()
    data class SetDicomFolder(val path: String) : WizardIntent()
    data class SetOutputFolder(val path: String) : WizardIntent()
    data class SetSlices(val slices: Int) : WizardIntent()
    data class SetPythonPath(val path: String) : WizardIntent()
    object VerifyPython : WizardIntent()
    data class SetPythonStatus(val status: PythonStatus) : WizardIntent()
    data class SetCheckpointExists(val exists: Boolean) : WizardIntent()
    object DownloadCheckpoint : WizardIntent()
    object CancelCheckpointDownload : WizardIntent()
    data class SetCheckpointDownload(val status: CheckpointDownload) : WizardIntent()
    object ProceedToPrompting : WizardIntent()
    object GoBack : WizardIntent()

    /** The padded cube finished loading; cache it in state so re-entering Prompting is instant. */
    data class DicomSeriesLoaded(val series: DicomSeries) : WizardIntent()

    data class AddPolylinePoint(
        val axis: Axis,
        val sliceIndex: Int,
        val x: Int,
        val y: Int,
        val mode: DrawingMode
    ) : WizardIntent()
    object EndPolyline : WizardIntent()
    data class DeleteLastPoint(
        val axis: Axis,
        val sliceIndex: Int,
        val mode: DrawingMode
    ) : WizardIntent()
    data class ClearSlice(val axis: Axis, val sliceIndex: Int) : WizardIntent()
    object RunPipeline : WizardIntent()
    object CancelPipeline : WizardIntent()
    object StartOver : WizardIntent()
    data class PipelineComplete(val outputPath: String) : WizardIntent()
}
