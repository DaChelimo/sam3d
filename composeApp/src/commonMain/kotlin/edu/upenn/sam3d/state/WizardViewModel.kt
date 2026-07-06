package edu.upenn.sam3d.state

import edu.upenn.sam3d.domain.model.AppView
import edu.upenn.sam3d.domain.model.PipelineProgress
import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.domain.model.RunReport
import edu.upenn.sam3d.domain.model.RunStatus
import edu.upenn.sam3d.domain.model.SliceAnnotation
import edu.upenn.sam3d.domain.model.WizardStep
import edu.upenn.sam3d.domain.model.embedVoxel
import edu.upenn.sam3d.domain.repository.RunReportRepository
import edu.upenn.sam3d.domain.usecase.AnnotationSaver
import edu.upenn.sam3d.domain.usecase.DicomDownsampler
import edu.upenn.sam3d.domain.usecase.PipelineRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val dicomDownsampler: DicomDownsampler? = null,
    private val reportStore: RunReportRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(WizardState())
    val state: StateFlow<WizardState> = _state.asStateFlow()

    // The in-flight Draft downsampling job, so a quality/folder change can cancel a stale one.
    private var downsampleJob: Job? = null

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
                        PipelineStage.COMPLETE -> {
                            _state.update {
                                it.copy(currentStep = WizardStep.DONE, outputGcodePath = progress.outputPath)
                            }
                            recordRun(progress, RunStatus.COMPLETE)
                        }
                        PipelineStage.ERROR -> {
                            val out = runner.recentOutput()
                            _state.update {
                                it.copy(
                                    error = PipelineError.Server(
                                        code = progress.exitCode ?: 1,
                                        body = out,
                                        logPath = runner.logPath(),
                                        hint = FailureHints.classify(out, progress.exitCode),
                                    )
                                )
                            }
                            recordRun(progress, RunStatus.ERROR)
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

            is WizardIntent.SetDicomFolder -> {
                // New folder ⇒ drop the cached cube so Prompting reloads the correct series, then
                // resolve the effective path (downsampled copy in Draft) for the new folder.
                _state.update { it.copy(dicomFolderPath = intent.path, dicomSeries = null) }
                recomputeEffectiveDicomPath()
            }

            is WizardIntent.SetOutputFolder ->
                _state.update { it.copy(outputFolderPath = intent.path) }

            is WizardIntent.SetSlices ->
                _state.update { it.copy(slices = intent.slices) }

            is WizardIntent.SetQuality -> {
                // Quality moves two levers together (slices + downsample) and changes the cube, so
                // drop the cached series and re-resolve the effective DICOM path.
                _state.update { it.copy(quality = intent.quality, slices = intent.quality.slices, dicomSeries = null) }
                recomputeEffectiveDicomPath()
            }

            is WizardIntent.SetEffectiveDicomPath ->
                _state.update { it.copy(effectiveDicomPath = intent.path, dicomSeries = null) }

            is WizardIntent.SetDownsampleStatus ->
                _state.update { it.copy(dicomDownsampleStatus = intent.status) }

            is WizardIntent.SetPythonPath ->
                _state.update { it.copy(pythonPath = intent.path, pythonStatus = PythonStatus.UNCHECKED) }

            WizardIntent.VerifyPython ->
                _state.update { it.copy(pythonStatus = PythonStatus.CHECKING) }

            is WizardIntent.SetPythonStatus ->
                _state.update { it.copy(pythonStatus = intent.status) }

            is WizardIntent.SetCheckpointExists ->
                _state.update {
                    it.copy(
                        checkpointExists = intent.exists,
                        // Found ⇒ clear any download UI; the file is in place.
                        checkpointDownload = if (intent.exists) CheckpointDownload.Idle else it.checkpointDownload,
                    )
                }

            // Signals the Start screen (jvmMain) to begin the native download; it streams progress
            // back via SetCheckpointDownload and flips SetCheckpointExists(true) on success.
            WizardIntent.DownloadCheckpoint ->
                _state.update { it.copy(checkpointDownload = CheckpointDownload.Connecting) }

            is WizardIntent.SetCheckpointDownload ->
                _state.update { it.copy(checkpointDownload = intent.status) }

            WizardIntent.CancelCheckpointDownload ->
                _state.update { it.copy(checkpointDownload = CheckpointDownload.Idle) }

            is WizardIntent.SetEnvSetup ->
                _state.update {
                    // On success the checkpoint is definitely present; flip the gate signal here. The
                    // Start screen also sets pythonPath→the venv (auto-verifies to VERIFIED) + persists.
                    it.copy(
                        envSetup = intent.status,
                        checkpointExists = if (intent.status is EnvSetup.Succeeded) true else it.checkpointExists,
                    )
                }

            WizardIntent.CancelEnvSetup ->
                _state.update { it.copy(envSetup = EnvSetup.Idle) }

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
                            // Engine reads the SAME folder the user annotated on (downsampled copy in
                            // Draft, original otherwise) so points.json coordinates line up.
                            dicomPath = snapshot.effectiveDicomPath ?: snapshot.dicomFolderPath,
                            outputDir = snapshot.outputFolderPath,
                            pythonExe = snapshot.pythonPath,
                            slices = snapshot.slices,
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

            is WizardIntent.SetAppView -> {
                _state.update { it.copy(appView = intent.view) }
                // Opening Reports pulls the latest from disk (a prior run may have appended since).
                if (intent.view == AppView.REPORTS) refreshReports()
            }
        }
    }

    /**
     * Build a [RunReport] from the terminal [progress]'s timing + the current config snapshot, surface
     * it on the Done screen ([WizardState.lastRunReport]), and persist it. No-op if timing is absent
     * (e.g. the parser's early COMPLETE before the process layer attaches it, or no runner in tests).
     */
    private fun recordRun(progress: PipelineProgress, status: RunStatus) {
        val timing = progress.timing ?: return
        val s = _state.value
        val report = RunReport(
            id = timing.id,
            startedAtEpochMs = timing.startedAtEpochMs,
            startedAtDisplay = timing.startedAtDisplay,
            quality = s.quality.label,
            slices = s.slices,
            downsampleTargetMaxDim = s.quality.downsampleTargetMaxDim,
            status = status,
            stages = timing.stages,
            totalSeconds = timing.totalSeconds,
            outputPath = if (status == RunStatus.COMPLETE) progress.outputPath else null,
        )
        _state.update { it.copy(lastRunReport = report) }
        val store = reportStore ?: return
        scope.launch {
            runCatching {
                store.append(report)
                _state.update { it.copy(reports = store.loadAll()) }
            }
        }
    }

    private fun refreshReports() {
        val store = reportStore ?: return
        scope.launch {
            runCatching { store.loadAll() }.getOrNull()?.let { all ->
                _state.update { it.copy(reports = all) }
            }
        }
    }

    /**
     * Resolve [WizardState.effectiveDicomPath] for the current folder + quality. Production (or a
     * missing folder / no downsampler) uses the original folder synchronously. Draft launches the
     * downsampler off-thread (cancelling any prior run), flips status to Generating, and on success
     * publishes the cached downsampled folder — used by BOTH the annotation loader and the engine so
     * the cubes match. On failure it falls back to the original folder so the app stays usable.
     */
    private fun recomputeEffectiveDicomPath() {
        downsampleJob?.cancel()
        val snapshot = _state.value
        val folder = snapshot.dicomFolderPath
        val target = snapshot.quality.downsampleTargetMaxDim
        if (folder == null) {
            _state.update { it.copy(effectiveDicomPath = null, dicomDownsampleStatus = DicomDownsampleStatus.Idle) }
            return
        }
        if (target == null || dicomDownsampler == null) {
            // Production, or no downsampler wired (tests/previews): use the scan as-is.
            _state.update { it.copy(effectiveDicomPath = folder, dicomDownsampleStatus = DicomDownsampleStatus.Idle) }
            return
        }
        _state.update { it.copy(effectiveDicomPath = null, dicomDownsampleStatus = DicomDownsampleStatus.Generating) }
        downsampleJob = scope.launch {
            runCatching { dicomDownsampler.ensureDownsampled(folder, target) }
                .onSuccess { path ->
                    _state.update { it.copy(effectiveDicomPath = path, dicomDownsampleStatus = DicomDownsampleStatus.Ready) }
                }
                .onFailure { e ->
                    // Fall back to the full-resolution folder (slow but correct) and surface why.
                    _state.update {
                        it.copy(
                            effectiveDicomPath = folder,
                            dicomDownsampleStatus = DicomDownsampleStatus.Failed(e.message ?: "Downsampling failed"),
                        )
                    }
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
