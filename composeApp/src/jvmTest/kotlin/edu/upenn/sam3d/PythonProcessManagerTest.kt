package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.process.PythonProcessManager
import edu.upenn.sam3d.process.StdoutProgressParser
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PythonProcessManagerTest {

    // Use /bin/sh as the "python" interpreter — shell scripts ignore unknown CLI flags.
    private val sh = Path.of("/bin/sh")

    // Process-spawning tests use /bin/sh and only run on Unix.
    private fun assumeUnix() = org.junit.Assume.assumeTrue(
        "Test requires /bin/sh — skipped on Windows",
        !System.getProperty("os.name", "").contains("Windows", ignoreCase = true),
    )

    @Test
    fun `command omits -v (would UnboundLocalError in sam3d_py) and sets the core flags`() {
        val manager = PythonProcessManager(sh, sh, sh.parent, StdoutProgressParser())
        val cmd = manager.buildCommand(sh.parent, sh.parent)
        assertFalse("-v" in cmd, "passing -v makes args.version the string \"1\" → predictor UnboundLocalError")
        assertTrue(
            cmd.containsAll(listOf("--reslice", "0", "--reprompt", "0", "-r", "ico", "--datatype", "dcm")),
            "core invocation flags must be present: $cmd",
        )
    }

    @Test
    fun `exit code 0 emits COMPLETE stage`() = runBlocking {
        assumeUnix()
        val script = shellScript("exit 0")
        val manager = PythonProcessManager(
            pythonExe = sh,
            sam3dScript = script,
            workingDir = script.parent,
            parser = StdoutProgressParser()
        )
        manager.start(dicomPath = script.parent, outputDir = script.parent).join()
        assertEquals(PipelineStage.COMPLETE, manager.progress.value?.stage)
    }

    @Test
    fun `non-zero exit code emits ERROR stage`() = runBlocking {
        assumeUnix()
        val script = shellScript("exit 1")
        val manager = PythonProcessManager(
            pythonExe = sh,
            sam3dScript = script,
            workingDir = script.parent,
            parser = StdoutProgressParser()
        )
        manager.start(dicomPath = script.parent, outputDir = script.parent).join()
        assertEquals(PipelineStage.ERROR, manager.progress.value?.stage)
    }

    @Test
    fun `a silent (no-output) run is killed by the inactivity watchdog and emits ERROR`() = runBlocking {
        assumeUnix()
        val script = shellScript("sleep 30")   // emits nothing → trips the inactivity timer
        val manager = PythonProcessManager(
            pythonExe = sh,
            sam3dScript = script,
            workingDir = script.parent,
            parser = StdoutProgressParser(),
            inactivityMs = 400L,                // §2: stop after 400 ms of no output
        )
        manager.start(dicomPath = script.parent, outputDir = script.parent).join()
        assertEquals(PipelineStage.ERROR, manager.progress.value?.stage)
        assertTrue(
            manager.recentOutput().contains("stopped", ignoreCase = true),
            "stop reason should be captured for the error dialog: ${manager.recentOutput()}",
        )
    }

    @Test
    fun `a run that keeps emitting output is NOT killed by the inactivity watchdog`() = runBlocking {
        assumeUnix()
        // Prints a line every 0.1s for ~1s — well past the 400ms inactivity window, but never idle.
        val script = shellScript("for i in 1 2 3 4 5 6 7 8 9 10; do echo line\$i; sleep 0.1; done; exit 0")
        val manager = PythonProcessManager(
            pythonExe = sh,
            sam3dScript = script,
            workingDir = script.parent,
            parser = StdoutProgressParser(),
            inactivityMs = 400L,
        )
        manager.start(dicomPath = script.parent, outputDir = script.parent).join()
        assertEquals(PipelineStage.COMPLETE, manager.progress.value?.stage,
            "a steadily-progressing run must survive the inactivity watchdog")
    }

    @Test
    fun `a process killed out from under us is detected promptly, not just via the inactivity timeout`() = runBlocking {
        assumeUnix()
        // Reproduces what was observed on a real Production run killed by the OS under memory
        // pressure: `proc.isAlive` goes false, but a still-alive descendant (here, a background job
        // that duplicated the stdout fd) keeps the pipe's write end open, so a plain blocking read
        // would never see EOF on its own. inactivityMs is set much higher than the ~200ms self-kill so
        // a pass here proves detection comes from the isAlive-polling watchdog, not the slow fallback.
        val script = shellScript("exec 3>&1\n(sleep 10 >&3) &\nsleep 0.2\nkill -9 \$\$\n")
        val manager = PythonProcessManager(
            pythonExe = sh,
            sam3dScript = script,
            workingDir = script.parent,
            parser = StdoutProgressParser(),
            inactivityMs = 20_000L,
        )
        val start = System.currentTimeMillis()
        manager.start(dicomPath = script.parent, outputDir = script.parent).join()
        val elapsed = System.currentTimeMillis() - start
        assertEquals(PipelineStage.ERROR, manager.progress.value?.stage)
        assertTrue(
            elapsed < 9_000,
            "should detect the dead process via isAlive polling well before the lingering fd's 10s " +
                "hold or the 20s inactivity timeout (took ${elapsed}ms)",
        )
        assertEquals(137, manager.progress.value?.exitCode, "SIGKILL should report as exit code 137")
    }

    private fun shellScript(body: String): Path {
        val f = createTempFile(suffix = ".sh")
        f.toFile().setExecutable(true)
        f.writeText("#!/bin/sh\n$body\n")
        return f
    }
}
