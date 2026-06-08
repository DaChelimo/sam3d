package edu.upenn.sam3d.state

/**
 * UI-facing state of the Draft downsampling step (mirrors [CheckpointDownload]'s shape). Lives in
 * commonMain (no java.*); the actual work is done by jvmMain's `Dcm4cheDownsampler` and surfaced via
 * [WizardState.dicomDownsampleStatus].
 */
sealed interface DicomDownsampleStatus {
    /** Nothing to do — Production (full resolution), or no folder chosen yet. */
    object Idle : DicomDownsampleStatus

    /** Building (or reusing a cached) reduced series; the Prompting canvas should wait. */
    object Generating : DicomDownsampleStatus

    /** The downsampled copy is ready; [WizardState.effectiveDicomPath] points at it. */
    object Ready : DicomDownsampleStatus

    /** Generation failed; [message] is safe to show. The app falls back to the original folder. */
    data class Failed(val message: String) : DicomDownsampleStatus

    val isGenerating: Boolean get() = this is Generating
}
