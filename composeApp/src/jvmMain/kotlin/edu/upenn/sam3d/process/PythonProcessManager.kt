package edu.upenn.sam3d.process

import edu.upenn.sam3d.AppConfig
import edu.upenn.sam3d.domain.model.PipelineProgress
import edu.upenn.sam3d.domain.model.PipelineStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Owns the sam3d.py subprocess lifecycle (§6.1). Spawns it with working dir = [workingDir] (the
 * SAM3D-GCODE root — critical for the relative `tempdir/`, `checkpoints/`, `outputs/` paths),
 * streams merged stdout/stderr through [parser], and exposes [progress].
 *
 * Two things the original Phase-1 skeleton lacked, added for Phase 5 (both verified against a real
 * run that otherwise dies / hangs):
 *  - Writes "done\n" to the subprocess stdin so sam3d.py's interactive point-cloud `input()` loop
 *    accepts defaults instead of throwing EOFError headlessly.
 *  - On exit 0, resolves the produced `output.gcode` path (§7.5) and emits COMPLETE with it.
 */
class PythonProcessManager(
    private val pythonExe: Path,
    private val sam3dScript: Path,
    private val workingDir: Path,
    private val parser: StdoutProgressParser,
    private val logDir: Path? = null,
) {
    private var process: Process? = null
    @Volatile private var cancelled = false

    private val _progress = MutableStateFlow<PipelineProgress?>(null)
    val progress: StateFlow<PipelineProgress?> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val recentLines = ArrayDeque<String>()
    private val recentCap = 50

    @Synchronized private fun remember(line: String) {
        recentLines.addLast(line)
        while (recentLines.size > recentCap) recentLines.removeFirst()
    }

    /** Last [lines] lines of subprocess output — shown in the error dialog (§ STEP 7). */
    @Synchronized fun recentOutput(lines: Int = 20): String =
        recentLines.toList().takeLast(lines).joinToString("\n")

    /**
     * The sam3d.py argv. Extracted (internal) so a test can assert it never re-introduces `-v`.
     *
     * NB: do NOT pass "-v 1". sam3d.py does `if args.version == 1:` (int) but argparse gives the CLI
     * value as the string "1", and "1" == 1 is False → `predictor` is never created →
     * UnboundLocalError at sam3d.py:156. Omitting -v keeps the working int default (1 = SAM v1 /
     * vit_h). Cannot fix Python (engine is read-only).
     */
    internal fun buildCommand(dicomPath: Path, outputDir: Path): List<String> = listOf(
        pythonExe.toString(), sam3dScript.toString(),
        "--reslice", "0",   // Kotlin already wrote tempdir/points.json — don't wipe it
        "--reprompt", "0",  // skip the Tk annotation GUI
        "-p", dicomPath.toString(),
        "-o", outputDir.toString(),
        "-r", AppConfig.PipelineDefaults.ROTATIONS,
        "-s", AppConfig.PipelineDefaults.SLICES.toString(),
        "--checkpoint", AppConfig.PipelineDefaults.CHECKPOINT,
        "--datatype", AppConfig.PipelineDefaults.DATATYPE,
    )

    fun start(dicomPath: Path, outputDir: Path): Job {
        cancelled = false
        return scope.launch {
            val cmd = buildCommand(dicomPath, outputDir)
            val proc = ProcessBuilder(cmd)
                .directory(workingDir.toFile())   // CRITICAL: relative paths in sam3d.py need this
                .redirectErrorStream(true)
                .start()
            process = proc

            // CRITICAL: sam3d.py blocks on input() in the point-cloud refinement loop. Feed "done"
            // so it accepts defaults and proceeds to G-code; without it the subprocess EOFErrors.
            // The bytes sit in the pipe until input() reads them; closing our end after is fine.
            runCatching {
                proc.outputStream.bufferedWriter().use { w ->
                    w.write("done\n")
                    w.flush()
                }
            }

            val logWriter = logDir?.let { dir ->
                runCatching {
                    Files.createDirectories(dir)
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    Files.newBufferedWriter(dir.resolve("sam3d-$stamp.log"))
                }.getOrNull()
            }

            try {
                proc.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        remember(line)
                        logWriter?.let { runCatching { it.write(line); it.newLine() } }
                        parser.parseLine(line)?.let { _progress.value = it }
                    }
                }
            } finally {
                logWriter?.let { runCatching { it.flush(); it.close() } }
            }

            val exitCode = proc.waitFor()
            when {
                cancelled -> Unit  // cancel() already cleared progress; don't emit ERROR
                exitCode == 0 -> {
                    val gcode = outputDir.resolve("output.gcode")
                    val path = if (Files.exists(gcode)) gcode.toString()
                    else newestGcode(outputDir) ?: gcode.toString()
                    _progress.value = PipelineProgress(PipelineStage.COMPLETE, outputPath = path)
                }
                else -> _progress.value = PipelineProgress(PipelineStage.ERROR)
            }
        }
    }

    private fun newestGcode(dir: Path): String? = runCatching {
        Files.list(dir).use { stream ->
            stream.filter { it.toString().endsWith(".gcode") }
                .max(compareBy { Files.getLastModifiedTime(it) })
                .map { it.toString() }
                .orElse(null)
        }
    }.getOrNull()

    fun cancel() {
        cancelled = true
        process?.destroyForcibly()
        process = null
        _progress.value = null
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
