package edu.upenn.sam3d.state

import edu.upenn.sam3d.domain.model.AppView
import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.domain.model.DicomSeries
import edu.upenn.sam3d.domain.model.QualityPreset

sealed class WizardIntent {
    data class SetSam3dGcodeDir(val path: String) : WizardIntent()
    data class SetDicomFolder(val path: String) : WizardIntent()
    data class SetOutputFolder(val path: String) : WizardIntent()
    data class SetSlices(val slices: Int) : WizardIntent()

    /** Pick the Draft/Production quality preset — sets `-s` slices AND drives downsampling. */
    data class SetQuality(val quality: QualityPreset) : WizardIntent()

    /** The resolved DICOM path (downsampled copy or original) used by annotation + the engine. */
    data class SetEffectiveDicomPath(val path: String?) : WizardIntent()
    data class SetDownsampleStatus(val status: DicomDownsampleStatus) : WizardIntent()
    data class SetPythonPath(val path: String) : WizardIntent()
    object VerifyPython : WizardIntent()
    data class SetPythonStatus(val status: PythonStatus) : WizardIntent()
    data class SetCheckpointExists(val exists: Boolean) : WizardIntent()
    object DownloadCheckpoint : WizardIntent()
    object CancelCheckpointDownload : WizardIntent()
    data class SetCheckpointDownload(val status: CheckpointDownload) : WizardIntent()

    /** One-click environment setup (venv + deps + checkpoint). The Start screen owns the manager
     *  (start/cancel/proceed are direct calls on it) and streams progress back via [SetEnvSetup];
     *  the ViewModel only reflects it into state. [CancelEnvSetup] resets the state to Idle. */
    data class SetEnvSetup(val status: EnvSetup) : WizardIntent()
    object CancelEnvSetup : WizardIntent()
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

    /** Switch the top-level destination (wizard ↔ Reports tab). Loads reports when opening REPORTS. */
    data class SetAppView(val view: AppView) : WizardIntent()
}
