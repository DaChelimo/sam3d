package edu.upenn.sam3d.domain.usecase

import edu.upenn.sam3d.domain.model.AnnotationFile
import edu.upenn.sam3d.domain.model.SliceAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator

/**
 * Converts the wizard's per-slice annotations into the single tempdir/points.json file that
 * sam3d.py feeds to scale_transform.parse_prompts (§9), then writes it.
 *
 * Mirrors reprompting3d.py:save_points() — top-level keys "positive"/"negative", each mapping to a
 * flat list of polylines, each polyline a list of [x, y, z] integer points already in padded-cube
 * voxel space with the slice index embedded at the active-axis position (§8.3). Empty polylines are
 * dropped, exactly as save_points() does (`[p for p in polylines if p]`).
 *
 * Lives in jvmMain (not commonMain) because writing the file uses java.nio; the conversion half is
 * pure and could move to commonMain later. Implements [AnnotationSaver] so the commonMain
 * WizardViewModel can trigger the write without depending on java.* (§2 non-negotiable #3).
 */
@OptIn(ExperimentalSerializationApi::class)
class SaveAnnotationsUseCase(
    // 4-space indent mirrors Python's json.dump(indent=4); makes points.json readable in an editor.
    private val json: Json = Json { prettyPrint = true; prettyPrintIndent = "    " },
) : AnnotationSaver {

    /** Pure mapping: List<SliceAnnotation> → the JSON-shaped [AnnotationFile] (§9.2). */
    fun toAnnotationFile(annotations: List<SliceAnnotation>): AnnotationFile {
        val positive = ArrayList<List<List<Int>>>()
        val negative = ArrayList<List<List<Int>>>()
        for (annotation in annotations) {
            annotation.positivePolylines.forEach { polyline ->
                if (polyline.isNotEmpty()) positive += polyline.map { it.toList() }
            }
            annotation.negativePolylines.forEach { polyline ->
                if (polyline.isNotEmpty()) negative += polyline.map { it.toList() }
            }
        }
        return AnnotationFile(positive = positive, negative = negative)
    }

    /** Serialises [annotations] to the points.json string (no I/O). */
    fun toJson(annotations: List<SliceAnnotation>): String =
        json.encodeToString(toAnnotationFile(annotations))

    /**
     * Writes <sam3dGcodeDir>/tempdir/points.json, deleting and recreating tempdir first so no stale
     * slice_NN.png / points.json from a previous run survives (§7.3). Returns the absolute path.
     */
    override suspend fun save(
        annotations: List<SliceAnnotation>,
        sam3dGcodeDir: String,
    ): String = withContext(Dispatchers.IO) {
        val tempdir: Path = Paths.get(sam3dGcodeDir, "tempdir")
        if (Files.exists(tempdir)) {
            Files.walk(tempdir).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        Files.createDirectories(tempdir)
        val pointsFile = tempdir.resolve("points.json")
        Files.writeString(pointsFile, toJson(annotations))
        pointsFile.toAbsolutePath().toString()
    }
}
