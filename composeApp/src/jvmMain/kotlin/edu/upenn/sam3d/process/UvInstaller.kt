package edu.upenn.sam3d.process

import edu.upenn.sam3d.OsUtils
import kotlinx.coroutines.ensureActive
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Fetches the [`uv`](https://github.com/astral-sh/uv) binary into the app data dir. `uv` is a single
 * self-contained executable from Astral that can **install Python itself**, create virtual
 * environments, and install packages — so the app can bootstrap a complete Python environment with
 * **no system Python required**. This is what lets the Setup screen drop the "Python binary" field and
 * the "you need Python 3.10–3.12" prerequisite entirely.
 *
 * The binary lands at `<userDataDir>/bin/uv` (`uv.exe` on Windows); [ensure] is idempotent (skips the
 * download if a working copy is already there) and cancellable via the calling coroutine.
 */
class UvInstaller(
    private val binDir: Path = OsUtils.userDataDir().resolve("bin"),
) {
    fun uvPath(): Path = binDir.resolve(if (OsUtils.isWindows()) "uv.exe" else "uv")

    /** True if a `uv` binary is present and runs. */
    fun isInstalled(): Boolean {
        val p = uvPath()
        if (!Files.exists(p)) return false
        return runCatching {
            val proc = ProcessBuilder(p.toString(), "--version").redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            proc.waitFor() == 0
        }.getOrDefault(false)
    }

    /** Download + extract `uv` if not already present, returning the binary path. */
    suspend fun ensure(): Path {
        val target = uvPath()
        if (isInstalled()) return target
        Files.createDirectories(binDir)
        val asset = assetName()
        val url = "$BASE_URL/$asset"
        val tmp = Files.createTempFile("uv-download", if (asset.endsWith(".zip")) ".zip" else ".tar.gz")
        try {
            downloadTo(url, tmp)
            val extractDir = Files.createTempDirectory("uv-extract")
            try {
                extract(tmp, extractDir)
                val bin = findBinary(extractDir)
                    ?: error("uv binary not found inside the downloaded archive ($asset)")
                Files.copy(bin, target, StandardCopyOption.REPLACE_EXISTING)
                if (!OsUtils.isWindows()) target.toFile().setExecutable(true, false)
            } finally {
                runCatching { extractDir.toFile().deleteRecursively() }
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
        return target
    }

    private suspend fun downloadTo(url: String, dest: Path) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            conn.inputStream.use { input ->
                Files.newOutputStream(dest).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()  // cooperative cancellation
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun extract(archive: Path, destDir: Path) {
        if (archive.toString().endsWith(".zip")) {
            ZipInputStream(Files.newInputStream(archive)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val outPath = destDir.resolve(entry.name).normalize()
                    if (outPath.startsWith(destDir)) {   // zip-slip guard
                        if (entry.isDirectory) {
                            Files.createDirectories(outPath)
                        } else {
                            Files.createDirectories(outPath.parent)
                            Files.newOutputStream(outPath).use { zin.copyTo(it) }
                        }
                    }
                    entry = zin.nextEntry
                }
            }
        } else {
            // Use the system `tar` for .tar.gz — present on macOS/Linux, and Windows 10+ ships bsdtar.
            val proc = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", destDir.toString())
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) error("Failed to extract the uv archive: ${out.take(300)}")
        }
    }

    private fun findBinary(root: Path): Path? {
        val name = if (OsUtils.isWindows()) "uv.exe" else "uv"
        Files.walk(root).use { stream ->
            return stream.filter { Files.isRegularFile(it) && it.fileName.toString() == name }
                .findFirst().orElse(null)
        }
    }

    /** The release asset for this OS + CPU. Names follow uv's stable cargo-dist scheme. */
    private fun assetName(): String {
        val arch = when (val a = System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x86_64"
            else -> error("Unsupported CPU architecture for uv: $a")
        }
        return when {
            OsUtils.isMac() -> "uv-$arch-apple-darwin.tar.gz"
            OsUtils.isWindows() -> "uv-$arch-pc-windows-msvc.zip"
            else -> "uv-$arch-unknown-linux-gnu.tar.gz"
        }
    }

    companion object {
        // GitHub's `latest` alias always resolves to the newest release's asset of the given name.
        // To pin a specific uv version for reproducible builds, swap this for
        // "https://github.com/astral-sh/uv/releases/download/<version>".
        const val BASE_URL = "https://github.com/astral-sh/uv/releases/latest/download"
    }
}
