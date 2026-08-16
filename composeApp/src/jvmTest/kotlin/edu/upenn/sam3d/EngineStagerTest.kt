package edu.upenn.sam3d

import edu.upenn.sam3d.engine.EngineStager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Staging the engine out of a packaged app into a writable directory — the fix for the report that
 * started this: an installed build had no `pipeline/` at all, so the Setup screen dead-ended with
 * "run the app from the project root".
 */
class EngineStagerTest {

    private fun fakeEngine(dir: Path): Path {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(EngineStager.ENGINE_MARKER), "print('engine')")
        Files.writeString(dir.resolve("utils.py"), "x = 1")
        Files.writeString(dir.resolve("requirements.txt"), "numpy")
        return dir
    }

    @Test
    fun `staging copies the engine sources and creates the checkpoints dir`() {
        val tmp = Files.createTempDirectory("stager")
        try {
            val source = fakeEngine(tmp.resolve("source"))
            val target = EngineStager.stage(source, tmp.resolve("target"))

            assertTrue(Files.exists(target.resolve(EngineStager.ENGINE_MARKER)))
            assertTrue(Files.exists(target.resolve("utils.py")))
            assertEquals("numpy", Files.readString(target.resolve("requirements.txt")).trim())
            assertTrue(Files.isDirectory(target.resolve("checkpoints")), "the engine resolves this relatively")
            assertTrue(EngineStager.isEngineDir(target.toString()))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * The reason staging exists at all: an app update must not cost the user another 2.4 GB download.
     */
    @Test
    fun `re-staging preserves a downloaded checkpoint and other runtime state`() {
        val tmp = Files.createTempDirectory("stager")
        try {
            val source = fakeEngine(tmp.resolve("source"))
            val target = EngineStager.stage(source, tmp.resolve("target"))

            val checkpoint = target.resolve("checkpoints/sam_vit_h_4b8939.pth")
            Files.writeString(checkpoint, "pretend 2.4 GB of weights")
            Files.createDirectories(target.resolve("tempdir"))
            Files.writeString(target.resolve("tempdir/points.json"), "{}")

            EngineStager.stage(source, target)   // e.g. the next app launch

            assertEquals("pretend 2.4 GB of weights", Files.readString(checkpoint))
            assertTrue(Files.exists(target.resolve("tempdir/points.json")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an updated source file is re-copied on the next launch`() {
        val tmp = Files.createTempDirectory("stager")
        try {
            val source = fakeEngine(tmp.resolve("source"))
            val target = EngineStager.stage(source, tmp.resolve("target"))
            assertEquals("x = 1", Files.readString(target.resolve("utils.py")).trim())

            // Simulate an app update shipping new engine sources.
            val updated = source.resolve("utils.py")
            Files.writeString(updated, "x = 2  # shipped by a newer build")
            Files.setLastModifiedTime(updated, FileTime.fromMillis(System.currentTimeMillis() + 10_000))

            EngineStager.stage(source, target)

            assertTrue(Files.readString(target.resolve("utils.py")).startsWith("x = 2"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `staging is idempotent and leaves unchanged files alone`() {
        val tmp = Files.createTempDirectory("stager")
        try {
            val source = fakeEngine(tmp.resolve("source"))
            val target = EngineStager.stage(source, tmp.resolve("target"))
            val stampBefore = Files.getLastModifiedTime(target.resolve("utils.py"))

            EngineStager.stage(source, target)

            assertEquals(stampBefore, Files.getLastModifiedTime(target.resolve("utils.py")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `isEngineDir rejects a folder without the entry point`() {
        val tmp = Files.createTempDirectory("stager")
        try {
            Files.createDirectories(tmp.resolve("not-the-engine"))
            assertFalse(EngineStager.isEngineDir(tmp.resolve("not-the-engine").toString()))
            assertFalse(EngineStager.isEngineDir(tmp.resolve("does-not-exist").toString()))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the ancestor walk finds a checkout from a nested working directory`() {
        val tmp = Files.createTempDirectory("stager")
        try {
            val root = tmp.resolve("repo")
            fakeEngine(root.resolve("pipeline"))
            val nested = root.resolve("composeApp/build/classes")
            Files.createDirectories(nested)

            val found = EngineStager.findInWorkingDirAncestors(nested)
            assertNotNull(found)
            assertEquals(root.resolve("pipeline").toRealPath(), found.toRealPath())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * The dev path, end to end: tests run with the working directory set to the `composeApp/` module,
     * which is exactly the case the ancestor walk exists for — `composeApp/pipeline` doesn't exist,
     * `../pipeline` does. If this breaks, running from a checkout silently starts staging into the
     * user data dir and re-downloading the 2.4 GB checkpoint.
     */
    @Test
    fun `a real checkout resolves to the repo's pipeline folder, used in place`() {
        val found = EngineStager.findInWorkingDirAncestors() ?: return  // packaged/CI without pipeline/
        assertEquals("pipeline", found.fileName.toString())
        assertTrue(Files.exists(found.resolve(EngineStager.ENGINE_MARKER)))
        assertEquals(found.toString(), EngineStager.resolve(), "resolve() must prefer the checkout")
    }

    @Test
    fun `the ancestor walk returns null when there is no checkout above`() {
        val tmp = Files.createTempDirectory("stager")
        try {
            val deep = tmp.resolve("a/b/c")
            Files.createDirectories(deep)
            assertNull(EngineStager.findInWorkingDirAncestors(deep))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
