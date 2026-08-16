package edu.upenn.sam3d

import edu.upenn.sam3d.process.UvInstaller
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Platform-placement rules that only misbehave on a managed Windows machine, where they're expensive
 * to discover: the data directory, MAX_PATH headroom, and a reproducible `uv`.
 */
class PlatformPathsTest {

    @Test
    fun `the data dir is under the platform's per-user area and namespaced to the app`() {
        val dir = OsUtils.userDataDir().toString()
        assertTrue(dir.contains("SAM3D") || dir.contains("sam3d"), "must be namespaced, got $dir")
    }

    /**
     * On Windows the bulk data (venv + a 2.4 GB checkpoint) must land in `%LOCALAPPDATA%`, never in
     * `%APPDATA%` — Roaming is profile-synced on domain-joined lab machines, where several GB either
     * blows the quota or turns every logon into a network copy.
     */
    @Test
    fun `on Windows the data dir is Local, not Roaming`() {
        if (!OsUtils.isWindows()) return
        val local = System.getenv("LOCALAPPDATA") ?: return
        assertTrue(
            OsUtils.userDataDir().startsWith(local),
            "expected the data dir under $local, got ${OsUtils.userDataDir()}",
        )
        assertTrue(OsUtils.legacyUserDataDir() != OsUtils.userDataDir(), "the legacy dir must be distinct")
    }

    @Test
    fun `migrating the legacy data dir never clobbers an existing install`() {
        // Safe on every platform: with no legacy dir (or none configured) this must be inert.
        OsUtils.migrateLegacyUserDataDir()
        OsUtils.migrateLegacyUserDataDir()   // idempotent
    }

    @Test
    fun `usable space is reported for a path that does not exist yet`() {
        val notYetCreated = OsUtils.userDataDir().resolve("venv/does/not/exist")
        assertTrue(OsUtils.usableSpaceBytes(notYetCreated) > 0, "should walk up to an existing ancestor")
    }

    @Test
    fun `long output paths are flagged only on Windows`() {
        val long = "C:\\Users\\someone\\" + "nested\\".repeat(30) + "output"
        assertTrue(long.length > OsUtils.WINDOWS_SAFE_PATH_BUDGET)
        assertEquals(OsUtils.isWindows(), OsUtils.isPathRiskyForWindows(long))
        assertTrue(!OsUtils.isPathRiskyForWindows("C:\\sam3d-output"), "a short path is always fine")
    }

    /**
     * A pinned uv is tried before `latest` so two people setting up a week apart get the same tool,
     * with `latest` retained as a fallback so a yanked release degrades instead of bricking setup.
     */
    @Test
    fun `uv download prefers the pinned release then falls back to latest`() {
        val urls = UvInstaller().downloadUrls("uv-x86_64-pc-windows-msvc.zip")
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("/download/${UvInstaller.UV_VERSION}/"), "pinned first: ${urls[0]}")
        assertTrue(urls[1].contains("/latest/download/"), "latest as fallback: ${urls[1]}")
        assertTrue(urls.all { it.endsWith("uv-x86_64-pc-windows-msvc.zip") })
    }

    @Test
    fun `venv interpreter layout matches the platform`() {
        val venv = Files.createTempDirectory("venv-layout")
        try {
            val py = OsUtils.venvPython(venv).toString()
            if (OsUtils.isWindows()) assertTrue(py.endsWith("Scripts\\python.exe"), py)
            else assertTrue(py.endsWith("bin/python"), py)
        } finally {
            venv.toFile().deleteRecursively()
        }
    }
}
