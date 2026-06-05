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
    private val slices: Int = AppConfig.slices,
    private val inactivityMs: Long = AppConfig.INACTIVITY_TIMEOUT_MS,
    private val maxRunMs: Long = AppConfig.MAX_RUN_MS,
    private val logDir: Path? = null,
    private val sleepInhibitor: SystemSleepInhibitor = SystemSleepInhibitor(),
) {
    private var process: Process? = null
    @Volatile private var cancelled = false
    @Volatile private var timedOut = false
    @Volatile private var logFilePath: Path? = null
    // Wall-clock of the last stdout line — drives the inactivity watchdog (§ sleep handling #2).
    @Volatile private var lastActivityAt = 0L

    /** Absolute path of this run's stdout log, or null if no log dir was configured (§ task 4). */
    fun logFile(): Path? = logFilePath

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
        "-s", slices.toString(),   // chosen on Setup via the quality toggle (Draft 8 / Production 120)
        "--checkpoint", AppConfig.PipelineDefaults.CHECKPOINT,
        "--datatype", AppConfig.PipelineDefaults.DATATYPE,
    )

    fun start(dicomPath: Path, outputDir: Path): Job {
        cancelled = false
        timedOut = false
        return scope.launch {
            val cmd = buildCommand(dicomPath, outputDir)
            val proc = ProcessBuilder(cmd)
                .directory(workingDir.toFile())   // CRITICAL: relative paths in sam3d.py need this
                .redirectErrorStream(true)
                .start()
            process = proc
            lastActivityAt = System.currentTimeMillis()

            // §1: keep the machine awake for the duration so a walk-away run finishes instead of
            // freezing on idle sleep. Released in the finally below (covers complete/cancel/error).
            sleepInhibitor.acquire()

            // §2 watchdog: kill ONLY when the run stops making progress (no new stdout for
            // [inactivityMs]) or blows past [maxRunMs] of *active* time — never on a fixed total
            // clock, so a healthy multi-hour Production run survives. Sleep is detected via an
            // oversized tick gap and excluded, so suspend/resume doesn't look like a stall.
            val watchdog = launch {
                val tick = (inactivityMs / 4).coerceIn(100L, 30_000L)
                var lastTick = System.currentTimeMillis()
                var activeElapsed = 0L
                while (true) {
                    kotlinx.coroutines.delay(tick)
                    if (!proc.isAlive || cancelled) break
                    val now = System.currentTimeMillis()
                    val gap = now - lastTick
                    lastTick = now
                    if (gap > tick * 4) {            // machine was asleep/suspended — don't penalise it
                        lastActivityAt = now
                        continue
                    }
                    activeElapsed += gap
                    val idleFor = now - lastActivityAt
                    if (idleFor > inactivityMs || activeElapsed > maxRunMs) {
                        timedOut = true
                        remember(
                            if (idleFor > inactivityMs)
                                "No new output for ${inactivityMs / 60_000} minutes — the run was stopped (it appears stuck)."
                            else "Exceeded the ${maxRunMs / 3_600_000} hour run limit — the run was stopped.",
                        )
                        proc.destroyForcibly()
                        break
                    }
                }
            }

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
                    val file = dir.resolve("sam3d-$stamp.log")
                    logFilePath = file
                    Files.newBufferedWriter(file)
                }.getOrNull()
            }

            try {
                proc.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        lastActivityAt = System.currentTimeMillis()  // progress → reset inactivity timer
                        remember(line)
                        logWriter?.let { runCatching { it.write(line); it.newLine() } }
                        parser.parseLine(line)?.let { _progress.value = it }
                    }
                }
            } finally {
                logWriter?.let { runCatching { it.flush(); it.close() } }
                sleepInhibitor.release()   // always let the machine sleep again once the run ends
            }

            val exitCode = proc.waitFor()
            watchdog.cancel()
            when {
                cancelled -> Unit  // cancel() already cleared progress; don't emit ERROR
                exitCode == 0 && !timedOut -> {
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
        sleepInhibitor.release()   // re-allow sleep immediately on cancel
        _progress.value = null
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
