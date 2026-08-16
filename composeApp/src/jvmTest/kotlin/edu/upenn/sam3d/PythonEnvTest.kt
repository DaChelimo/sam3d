package edu.upenn.sam3d

import edu.upenn.sam3d.process.PythonEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Guards the Windows-only crash that killed every lab run: `sam3d.py:112` prints
 * `f"Using device →  {device}"`, and on Windows a piped stdout is encoded with the ANSI code page
 * (cp1252), which has no mapping for U+2192.
 *
 * The end-to-end test below forces cp1252 explicitly rather than waiting for a Windows runner, so the
 * bug reproduces — and the fix is proven — on macOS and Linux too. Without that, the regression could
 * only ever be caught on one of the three CI targets.
 */
class PythonEnvTest {

    private val arrowScript = "print('Using device \\u2192  cpu')"

    /** `python3` on Unix, `python` on Windows; null when neither is on PATH (then the test skips). */
    private fun python(): String? = listOf("python3", "python").firstOrNull { exe ->
        runCatching {
            ProcessBuilder(exe, "-c", "pass").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }

    private fun runArrowPrint(env: Map<String, String>): Pair<Int, String> {
        val exe = python() ?: return 0 to "(skipped)"
        val builder = ProcessBuilder(exe, "-c", arrowScript).redirectErrorStream(true)
        builder.environment().putAll(env)
        val proc = builder.start()
        val out = proc.inputStream.bufferedReader().readText()
        return proc.waitFor() to out
    }

    @Test
    fun `the engine's arrow really does kill a cp1252 pipe — this is the bug`() {
        org.junit.Assume.assumeTrue("No python on PATH", python() != null)
        val (exit, out) = runArrowPrint(mapOf("PYTHONIOENCODING" to "cp1252"))
        assertNotEquals(0, exit, "expected the U+2192 print to fail under cp1252, got: $out")
        kotlin.test.assertTrue(
            "UnicodeEncodeError" in out,
            "expected a UnicodeEncodeError from the cp1252 codec, got: $out",
        )
    }

    @Test
    fun `PythonEnv makes the same print succeed`() {
        org.junit.Assume.assumeTrue("No python on PATH", python() != null)
        // Start from the broken code page, exactly as an inherited Windows environment would, and
        // prove PythonEnv overrides it rather than merely filling in a blank.
        val env = HashMap<String, String>().apply { put("PYTHONIOENCODING", "cp1252") }
        PythonEnv.apply(env)
        val (exit, out) = runArrowPrint(env)
        assertEquals(0, exit, "PythonEnv must make the arrow printable: $out")
        kotlin.test.assertTrue("Using device" in out, "expected the line to be printed, got: $out")
    }

    @Test
    fun `apply sets every variable the engine depends on`() {
        val env = HashMap<String, String>()
        PythonEnv.apply(env)
        assertEquals("utf-8", env["PYTHONIOENCODING"], "stdout/stderr encoding — the crash")
        assertEquals("1", env["PYTHONUTF8"], "open() defaults — points.json, the G-code writer")
        assertEquals("1", env["PYTHONUNBUFFERED"], "block-buffered stdout starves the progress parser")
    }
}
