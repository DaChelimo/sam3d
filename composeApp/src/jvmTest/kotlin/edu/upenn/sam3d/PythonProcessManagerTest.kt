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

    private fun shellScript(body: String): Path {
        val f = createTempFile(suffix = ".sh")
        f.toFile().setExecutable(true)
        f.writeText("#!/bin/sh\n$body\n")
        return f
    }
}
