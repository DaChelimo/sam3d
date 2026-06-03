package edu.upenn.sam3d.state

/**
 * UI-facing state of the SAM checkpoint download (§6.x / Phase 6 task 4). Lives in commonMain (no
 * java.*); the actual HTTP streaming is done by jvmMain's `CheckpointDownloader`, which pushes these
 * values into [WizardState.checkpointDownload]. The 2.4 GB file makes a real progress read essential,
 * so [InProgress] carries the byte counts and derives a [fraction] when the server sends a length.
 */
sealed interface CheckpointDownload {
    /** Nothing happening (default, and the resting state after success/cancel). */
    object Idle : CheckpointDownload

    /** Request sent, awaiting the first bytes — render as indeterminate. */
    object Connecting : CheckpointDownload

    /** Streaming. [totalBytes] is null when the server omits Content-Length. */
    data class InProgress(val receivedBytes: Long, val totalBytes: Long?) : CheckpointDownload {
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }
                ?.let { (receivedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    }

    /** File is fully downloaded and moved into place. */
    object Succeeded : CheckpointDownload

    /** Download failed; [message] is safe to show the user. */
    data class Failed(val message: String) : CheckpointDownload

    val isActive: Boolean get() = this is Connecting || this is InProgress
}
