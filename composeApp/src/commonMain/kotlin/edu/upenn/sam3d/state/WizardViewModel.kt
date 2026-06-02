package edu.upenn.sam3d.state

import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.domain.model.SliceAnnotation
import edu.upenn.sam3d.domain.model.WizardStep
import edu.upenn.sam3d.domain.model.embedVoxel
import edu.upenn.sam3d.domain.usecase.AnnotationSaver
import edu.upenn.sam3d.domain.usecase.PipelineRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @param annotationSaver writes tempdir/points.json on RunPipeline. Optional so tests and previews
 *   can construct the ViewModel with no JVM dependencies; null = skip the write.
 * @param scope where the points.json write is launched (Dispatchers.Default — the use case itself
 *   switches to Dispatchers.IO, which is JVM-only and unavailable in commonMain).
 */
class WizardViewModel(
    private val annotationSaver: AnnotationSaver? = null,
    private val pipelineRunner: PipelineRunner? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(WizardState())
    val state: StateFlow<WizardState> = _state.asStateFlow()

    // Transient drawing state (not part of WizardState): when true, the next AddPolylinePoint starts
    // a fresh polyline instead of extending the current one. Set by EndPolyline and by slice/axis
    // changes — mirroring reprompting3d.py's start_new_polyline(force_new=True) on those events.
    private var pendingNewPolyline = true

    init {
        // Reflect sam3d.py progress into state: auto-advance to DONE on COMPLETE, raise the error
        // dialog (last log lines) on ERROR (§ STEP 7).
        val runner = pipelineRunner
        if (runner != null) {
            scope.launch {
                runner.progress.collect { progress ->
                    if (progress == null) return@collect
                    _state.update { it.copy(pipelineProgress = progress) }
                    when (progress.stage) {
                        PipelineStage.COMPLETE -> _state.update {
                            it.copy(currentStep = WizardStep.DONE, outputGcodePath = progress.outputPath)
                        }
                        PipelineStage.ERROR -> _state.update {
                            it.copy(error = PipelineError.Server(code = 1, body = runner.recentOutput()))
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    fun handle(intent: WizardIntent) {
        when (intent) {
            is WizardIntent.SetSam3dGcodeDir ->
                _state.update { it.copy(sam3dGcodeDir = intent.path, checkpointExists = false) }

            is WizardIntent.SetDicomFolder ->
                // New folder ⇒ drop the cached cube so Prompting reloads the correct series.
                _state.update { it.copy(dicomFolderPath = intent.path, dicomSeries = null) }

            is WizardIntent.SetOutputFolder ->
                _state.update { it.copy(outputFolderPath = intent.path) }

            is WizardIntent.SetPythonPath ->
                _state.update { it.copy(pythonPath = intent.path, pythonStatus = PythonStatus.UNCHECKED) }

            WizardIntent.VerifyPython ->
                _state.update { it.copy(pythonStatus = PythonStatus.CHECKING) }

            is WizardIntent.SetPythonStatus ->
                _state.update { it.copy(pythonStatus = intent.status) }

            is WizardIntent.SetCheckpointExists ->
                _state.update { it.copy(checkpointExists = intent.exists) }

            WizardIntent.DownloadCheckpoint -> Unit

            WizardIntent.ProceedToPrompting ->
                _state.update { it.copy(currentStep = WizardStep.PROMPTING) }

            WizardIntent.GoBack -> when (_state.value.currentStep) {
                WizardStep.PROMPTING -> _state.update { it.copy(currentStep = WizardStep.START) }
                else -> Unit
            }

            is WizardIntent.DicomSeriesLoaded ->
                _state.update { it.copy(dicomSeries = intent.series) }

            is WizardIntent.AddPolylinePoint ->
                _state.update { addPoint(it, intent) }

            WizardIntent.EndPolyline ->
                pendingNewPolyline = true

            is WizardIntent.DeleteLastPoint ->
                _state.update { deleteLastPoint(it, intent) }

            is WizardIntent.ClearSlice -> {
                pendingNewPolyline = true
                _state.update { s ->
                    s.copy(annotations = s.annotations.filter {
                        !(it.axis == intent.axis && it.sliceIndex == intent.sliceIndex)
                    })
                }
            }

            WizardIntent.RunPipeline -> {
                // §5.3 + §STEP 6: advance immediately (responsive UI), then off-thread write
                // tempdir/points.json and — only after that completes — spawn sam3d.py. Order
                // matters: the subprocess reads the file we just wrote.
                val snapshot = _state.value
                val saver = annotationSaver
                val runner = pipelineRunner
                _state.update { it.copy(currentStep = WizardStep.PROCESSING, error = null, pipelineProgress = null) }
                scope.launch {
                    if (saver != null && snapshot.sam3dGcodeDir != null) {
                        runCatching { saver.save(snapshot.annotations, snapshot.sam3dGcodeDir) }
                    }
                    if (runner != null &&
                        snapshot.sam3dGcodeDir != null &&
                        snapshot.dicomFolderPath != null &&
                        snapshot.outputFolderPath != null
                    ) {
                        runner.start(
                            sam3dGcodeDir = snapshot.sam3dGcodeDir,
                            dicomPath = snapshot.dicomFolderPath,
                            outputDir = snapshot.outputFolderPath,
                            pythonExe = snapshot.pythonPath,
                        )
                    }
                }
            }

            WizardIntent.CancelPipeline -> {
                pipelineRunner?.cancel()
                pendingNewPolyline = true
                _state.update { WizardState() }
            }

            WizardIntent.StartOver -> {
                pipelineRunner?.cancel()
                pendingNewPolyline = true
                _state.update { WizardState() }
            }

            is WizardIntent.PipelineComplete ->
                _state.update {
                    it.copy(
                        currentStep = WizardStep.DONE,
                        outputGcodePath = intent.outputPath
                    )
                }
        }
    }

    // ── Annotation accumulation ────────────────────────────────────────────────

    private fun addPoint(state: WizardState, intent: WizardIntent.AddPolylinePoint): WizardState {
        val point = embedVoxel(intent.axis, intent.sliceIndex, intent.x, intent.y)
        val annotations = state.annotations.toMutableList()
        val idx = annotations.indexOfFirst {
            it.axis == intent.axis && it.sliceIndex == intent.sliceIndex
        }
        val existing = if (idx >= 0) annotations[idx]
        else SliceAnnotation(intent.axis, intent.sliceIndex, emptyList(), emptyList())

        val isPositive = intent.mode == DrawingMode.POSITIVE
        val source = if (isPositive) existing.positivePolylines else existing.negativePolylines
        val updatedPolylines = appendToCurrentPolyline(source, point)
        pendingNewPolyline = false

        val updated =
            if (isPositive) existing.copy(positivePolylines = updatedPolylines)
            else existing.copy(negativePolylines = updatedPolylines)

        if (idx >= 0) annotations[idx] = updated else annotations.add(updated)
        return state.copy(annotations = annotations)
    }

    /** Starts a new polyline when [pendingNewPolyline]/empty, else extends the last one (deduping
     *  a repeated tap on the same voxel). Copies the lists so prior states stay immutable. */
    private fun appendToCurrentPolyline(
        polylines: List<List<IntArray>>,
        point: IntArray,
    ): List<List<IntArray>> {
        val result = polylines.mapTo(ArrayList()) { it.toMutableList() }
        if (pendingNewPolyline || result.isEmpty()) {
            result.add(mutableListOf(point))
        } else {
            val last = result.last()
            if (last.isEmpty() || !last.last().contentEquals(point)) last.add(point)
        }
        return result
    }

    private fun deleteLastPoint(state: WizardState, intent: WizardIntent.DeleteLastPoint): WizardState {
        val annotations = state.annotations.toMutableList()
        val idx = annotations.indexOfFirst {
            it.axis == intent.axis && it.sliceIndex == intent.sliceIndex
        }
        if (idx < 0) return state
        val annotation = annotations[idx]
        val isPositive = intent.mode == DrawingMode.POSITIVE
        val polylines = (if (isPositive) annotation.positivePolylines else annotation.negativePolylines)
            .mapTo(ArrayList()) { it.toMutableList() }

        val lastNonEmpty = polylines.indexOfLast { it.isNotEmpty() }
        if (lastNonEmpty < 0) return state
        polylines[lastNonEmpty].removeAt(polylines[lastNonEmpty].lastIndex)
        if (polylines[lastNonEmpty].isEmpty()) polylines.removeAt(lastNonEmpty)

        val updated =
            if (isPositive) annotation.copy(positivePolylines = polylines)
            else annotation.copy(negativePolylines = polylines)

        val isEmpty = updated.positivePolylines.all { it.isEmpty() } &&
            updated.negativePolylines.all { it.isEmpty() }
        if (isEmpty) annotations.removeAt(idx) else annotations[idx] = updated
        return state.copy(annotations = annotations)
    }
}
