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

    private fun shellScript(body: String): Path {
        val f = createTempFile(suffix = ".sh")
        f.toFile().setExecutable(true)
        f.writeText("#!/bin/sh\n$body\n")
        return f
    }
}
