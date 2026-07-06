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
import java.nio.file.StandardOpenOption
import kotlin.coroutines.coroutineContext

/**
 * Streams the 2.4 GB SAM ViT-H checkpoint straight to disk with true byte-level progress, and — as of
 * the one-click environment setup — **resumes** an interrupted download instead of restarting.
 *
 * Why native HTTP and not `python download_checkpoint.py` (as the original plan sketched): that engine
 * script uses `urllib.urlretrieve` with **no** progress output, so there is nothing to parse for a
 * live percentage — and the engine is read-only, so we can't add a tqdm hook. Doing the GET here
 * gives an exact `received / Content-Length` fraction, clean cancellation, and zero Python dependency.
 *
 * The file is written to `<name>.part` and atomically moved into place only on success. On cancel or
 * failure the `.part` is **kept** so a later run can resume via an HTTP `Range` request (if the CDN
 * answers `206`; a `200` means Range was ignored, so we truncate and start over). This class exposes
 * both a fire-and-forget [start]/[cancel] + [StateFlow] API (standalone use) and a suspend [download]
 * core that [EnvironmentSetupManager] awaits as one stage of the combined setup.
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
            runCatching {
                download(Path.of(sam3dGcodeDir, *CHECKPOINT_REL)) { received, total ->
                    _state.value = CheckpointDownload.InProgress(received, total)
                }
            }
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

    /**
     * Resumable GET of the checkpoint into [dest], reporting `(received, total)` as it streams (total is
     * null when the server sends no length). Cooperatively cancellable. Reused by [start] and by the
     * combined environment setup. Leaves the `.part` behind on interruption so the next call resumes.
     */
    suspend fun download(dest: Path, onProgress: (Long, Long?) -> Unit = { _, _ -> }) {
        if (Files.exists(dest)) return // already present → treat as success
        Files.createDirectories(dest.parent)
        val part = dest.resolveSibling("${dest.fileName}.part")
        try {
            attempt(part, dest, allowResume = true, onProgress = onProgress)
        } catch (_: RangeNotSatisfiable) {
            // Our `.part` is stale/complete-but-too-long for the server's file; discard and restart.
            runCatching { Files.deleteIfExists(part) }
            attempt(part, dest, allowResume = false, onProgress = onProgress)
        }
    }

    private class RangeNotSatisfiable : Exception()

    private suspend fun attempt(part: Path, dest: Path, allowResume: Boolean, onProgress: (Long, Long?) -> Unit) {
        val existing = if (allowResume && Files.exists(part)) Files.size(part) else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        connection = conn
        try {
            val code = conn.responseCode
            if (code == 416) throw RangeNotSatisfiable()  // Range Not Satisfiable → caller restarts fresh
            val resuming = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL  // 206: server honoured Range
            val remaining = conn.contentLengthLong.takeIf { it > 0 }
            val total: Long? = if (resuming)
                parseContentRangeTotal(conn.getHeaderField("Content-Range")) ?: remaining?.let { existing + it }
            else remaining

            // 206 → append after what we already have; anything else (incl. a 200 that ignored Range) →
            // truncate and write from zero so we never concatenate onto a stale prefix.
            val openOptions = if (resuming)
                arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)
            else
                arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

            var received = if (resuming) existing else 0L
            var lastEmitted = received
            onProgress(received, total)
            conn.inputStream.use { input ->
                Files.newOutputStream(part, *openOptions).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()  // cooperative cancellation between reads
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        // Throttle UI updates to ~every 2 MB so the flow isn't spammed.
                        if (received - lastEmitted >= 2_000_000L) {
                            onProgress(received, total)
                            lastEmitted = received
                        }
                    }
                }
            }
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // NOTE: the `.part` is intentionally NOT deleted on failure/cancel — that's what lets the
            // next run resume from where this one stopped.
            runCatching { conn.disconnect() }
            connection = null
        }
    }

    companion object {
        const val SAM_VIT_H_URL =
            "https://dl.fbaipublicfiles.com/segment_anything/sam_vit_h_4b8939.pth"
        /** Relative to the SAM3D-GCODE dir; matches AppConfig.PipelineDefaults.CHECKPOINT. */
        val CHECKPOINT_REL = arrayOf("checkpoints", "sam_vit_h_4b8939.pth")

        /** Extract the total size from a `Content-Range: bytes 200-1023/1024` header → 1024. Null if absent. */
        fun parseContentRangeTotal(header: String?): Long? {
            val slash = header?.substringAfterLast('/', "") ?: return null
            return slash.toLongOrNull()
        }
    }
}
