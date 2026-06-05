package edu.upenn.sam3d.process

import edu.upenn.sam3d.state.CheckpointDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/**
 * Streams the 2.4 GB SAM ViT-H checkpoint straight to disk with true byte-level progress.
 *
 * Why native HTTP and not `python download_checkpoint.py` (as the original plan sketched): that engine
 * script uses `urllib.urlretrieve` with **no** progress output, so there is nothing to parse for a
 * live percentage — and the engine is read-only, so we can't add a tqdm hook. Doing the GET here
 * gives an exact `received / Content-Length` fraction, clean cancellation, and zero Python dependency.
 *
 * Mirrors [PythonProcessManager]'s shape: a [StateFlow] of progress plus [start]/[cancel]. The file is
 * written to `<name>.part` and atomically moved into place only on success, so a cancelled or failed
 * download never leaves a truncated checkpoint that would later be mistaken for a valid one.
 */
class CheckpointDownloader(
    private val url: String = SAM_VIT_H_URL,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<CheckpointDownload>(CheckpointDownload.Idle)
    val state: StateFlow<CheckpointDownload> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var connection: HttpURLConnection? = null

    /** Begin downloading into `<sam3dGcodeDir>/checkpoints/sam_vit_h_4b8939.pth`. Idempotent restart. */
    fun start(sam3dGcodeDir: String) {
        cancel()
        _state.value = CheckpointDownload.Connecting
        job = scope.launch {
            runCatching { download(Path.of(sam3dGcodeDir, *CHECKPOINT_REL)) }
                .onSuccess { _state.value = CheckpointDownload.Succeeded }
                .onFailure { e ->
                    // A cancellation is a user action, not an error — fall back to Idle quietly.
                    _state.value =
                        if (e is kotlinx.coroutines.CancellationException) CheckpointDownload.Idle
                        else CheckpointDownload.Failed(e.message ?: "Download failed")
                }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        runCatching { connection?.disconnect() }
        connection = null
        if (_state.value.isActive) _state.value = CheckpointDownload.Idle
    }

    private suspend fun download(dest: Path) {
        if (Files.exists(dest)) return // already present → treat as success
        Files.createDirectories(dest.parent)
        val part = dest.resolveSibling("${dest.fileName}.part")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        connection = conn
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                Files.newOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var received = 0L
                    var lastEmitted = 0L
                    _state.value = CheckpointDownload.InProgress(0, total)
                    while (true) {
                        coroutineContext.ensureActive()  // cooperative cancellation between reads
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        // Throttle UI updates to ~every 2 MB so the flow isn't spammed.
                        if (received - lastEmitted >= 2_000_000L) {
                            _state.value = CheckpointDownload.InProgress(received, total)
                            lastEmitted = received
                        }
                    }
                }
            }
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(part) } // never leave a partial file behind
            throw t
        } finally {
            runCatching { conn.disconnect() }
            connection = null
        }
    }

    companion object {
        const val SAM_VIT_H_URL =
            "https://dl.fbaipublicfiles.com/segment_anything/sam_vit_h_4b8939.pth"
        /** Relative to the SAM3D-GCODE dir; matches AppConfig.PipelineDefaults.CHECKPOINT. */
        val CHECKPOINT_REL = arrayOf("checkpoints", "sam_vit_h_4b8939.pth")
    }
}
