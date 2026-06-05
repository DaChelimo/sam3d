package edu.upenn.sam3d.process

import edu.upenn.sam3d.domain.model.PipelineProgress
import edu.upenn.sam3d.domain.usecase.PipelineRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * jvmMain adapter wiring the commonMain [PipelineRunner] port to [PythonProcessManager]. Builds a
 * fresh manager per run from the (String) paths supplied by WizardState and forwards its progress.
 *
 * @param logDir where each run's stdout log is written (null = no file).
 * @param onManagerStarted hook so main.kt can register the live manager for its JVM shutdown hook.
 */
class PythonPipelineRunner(
    private val logDir: Path? = null,
    private val onManagerStarted: (PythonProcessManager) -> Unit = {},
) : PipelineRunner {

    private val _progress = MutableStateFlow<PipelineProgress?>(null)
    override val progress: StateFlow<PipelineProgress?> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var manager: PythonProcessManager? = null
    private var forwardJob: Job? = null

    override fun start(sam3dGcodeDir: String, dicomPath: String, outputDir: String, pythonExe: String, slices: Int) {
        val mgr = PythonProcessManager(
            pythonExe = Path.of(pythonExe),
            sam3dScript = Path.of(sam3dGcodeDir, "sam3d.py"),
            workingDir = Path.of(sam3dGcodeDir),
            parser = StdoutProgressParser(),
            slices = slices,
            logDir = logDir,
        )
        manager = mgr
        onManagerStarted(mgr)
        forwardJob?.cancel()
        forwardJob = scope.launch { mgr.progress.collect { _progress.value = it } }
        mgr.start(Path.of(dicomPath), Path.of(outputDir))
    }

    override fun cancel() {
        manager?.cancel()
        _progress.value = null
    }

    override fun recentOutput(): String = manager?.recentOutput() ?: ""

    override fun logPath(): String? = manager?.logFile()?.toString()
}
