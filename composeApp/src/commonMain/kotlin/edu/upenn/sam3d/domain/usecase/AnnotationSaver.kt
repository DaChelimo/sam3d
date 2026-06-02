package edu.upenn.sam3d.domain.usecase

import edu.upenn.sam3d.domain.model.SliceAnnotation

/**
 * Port (commonMain) the WizardViewModel calls when the user runs the pipeline.
 *
 * The implementation (SaveAnnotationsUseCase, jvmMain) performs the JVM file I/O — writing
 * tempdir/points.json under the SAM3D-GCODE directory — so commonMain stays free of java.* APIs
 * (Critical rule #4 / §2 non-negotiable #3). Returns the absolute path of the file written.
 */
fun interface AnnotationSaver {
    suspend fun save(annotations: List<SliceAnnotation>, sam3dGcodeDir: String): String
}
