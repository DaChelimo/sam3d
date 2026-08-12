package edu.upenn.sam3d.domain.usecase

import edu.upenn.sam3d.domain.model.UpdateStatus

/**
 * Looks up whether a newer build has been published. Implemented in jvmMain (HTTP to the GitHub
 * releases API) so commonMain stays free of java.* — same seam as [AnnotationSaver].
 */
fun interface UpdateSource {
    /** Never throws: any failure is reported as [UpdateStatus.Unknown]. */
    suspend fun check(): UpdateStatus
}
