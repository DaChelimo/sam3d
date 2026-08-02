package edu.upenn.sam3d

import edu.upenn.sam3d.engine.EngineStager
import edu.upenn.sam3d.process.CheckpointDownloader
import edu.upenn.sam3d.process.EnvironmentSetupManager
import edu.upenn.sam3d.state.EnvSetup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the **real** one-click environment setup end to end: downloads `uv`, installs a managed
 * CPython, builds a venv, installs the engine's dependencies from the rewritten requirements, and
 * verifies every module the engine imports. Only the 2.4 GB checkpoint download is substituted.
 *
 * ### Why this exists
 * Everything else in this suite is a unit test against code we control. This is the only check that
 * exercises the parts we *don't* control and that drift on their own schedule, with no commit of ours
 * involved:
 *
 *  - `uv` releases — that the pinned version still publishes the asset names [UvInstaller] builds.
 *  - PyPI — that `torch`, `open3d`, `fastkde`, `SimpleITK` and the rest still resolve for CPython
 *    3.11 on this platform *as wheels*, rather than as source that needs a compiler.
 *  - GitHub — that the `git`-free segment-anything archive URL still resolves.
 *  - The managed CPython build — above all that it ships **tkinter**, which the engine imports at
 *    module scope, which is not pip-installable, and which nothing else would catch until a user's
 *    first run fails.
 *
 * Run on Windows specifically because that's the deployment target whose failures cost the most to
 * discover: the report that prompted all of this came from a lab Windows machine.
 *
 * ### Opt-in
 * Skipped unless `SAM3D_ENV_SMOKE=true`, matching how `ScreenshotGenTest` gates itself. It takes
 * ~10–15 minutes and downloads a few GB, so it must never run in the ordinary unit-test job.
 */
class EnvironmentSetupSmokeTest {

    /** Stands in for the 2.4 GB download: writes a token file so the flow proceeds to verification. */
    private class StubCheckpoint : CheckpointDownloader() {
        override suspend fun download(dest: Path, onProgress: (Long, Long?) -> Unit) {
            Files.createDirectories(dest.parent)
            Files.writeString(dest, "stub checkpoint — the real download is covered separately")
            onProgress(1, 1)
        }
    }

    @Test
    fun `the real setup flow builds an environment that can import the whole engine`() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Opt-in end-to-end setup check — set SAM3D_ENV_SMOKE=true to run it",
            System.getenv("SAM3D_ENV_SMOKE") == "true",
        )

        val engine = EngineStager.findInWorkingDirAncestors()
            ?: fail("no pipeline/ in this checkout — the smoke test needs the vendored engine")

        val work = Files.createTempDirectory("sam3d-env-smoke")
        val manager = EnvironmentSetupManager(
            pipelineDir = engine.toString(),
            venvDir = work.resolve("venv"),
            logDir = work.resolve("logs"),
            checkpoint = StubCheckpoint(),
            minCheckpointBytes = 1,          // the stub writes a token, not 2.4 GB
        )

        try {
            manager.start()
            val terminal = withTimeout(TIMEOUT_MS) {
                manager.state.first { it is EnvSetup.Succeeded || it is EnvSetup.Failed }
            }

            if (terminal is EnvSetup.Failed) {
                val log = terminal.logPath?.let { runCatching { Files.readString(Path.of(it)) }.getOrNull() }
                fail(
                    "Environment setup failed at ${terminal.stage}: ${terminal.message}\n\n" +
                        "--- setup log ---\n${log?.takeLast(4000) ?: "(no log captured)"}",
                )
            }
            assertTrue(terminal is EnvSetup.Succeeded, "expected Succeeded, got $terminal")

            // The venv really exists and really runs, not just a state machine that said so.
            val python = OsUtils.venvPython(work.resolve("venv"))
            assertTrue(Files.exists(python), "no interpreter at $python")
            assertTrue(
                ProcessBuilder(python.toString(), "-c", "import segment_anything, torch, tkinter")
                    .redirectErrorStream(true).start().waitFor() == 0,
                "the built venv can't import the engine's key modules",
            )
        } finally {
            manager.cancel()
            runCatching { work.toFile().deleteRecursively() }
        }
    }

    private companion object {
        /** Generous: PyTorch alone is a multi-hundred-MB download on a cold CI runner. */
        const val TIMEOUT_MS = 40L * 60 * 1000
    }
}
