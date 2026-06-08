package edu.upenn.sam3d.process

import edu.upenn.sam3d.OsUtils
import edu.upenn.sam3d.domain.model.RunReport
import edu.upenn.sam3d.domain.repository.RunReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * jvmMain implementation of [RunReportRepository]: persists run reports as a single JSON array at
 * `<dir>/reports.json` (default `<userDataDir>/SAM3D/reports.json`, alongside config.json and logs).
 * Newest-first, capped at [maxReports] so the history can't grow without bound. Exception-safe —
 * a missing/corrupt file reads back as empty rather than crashing the app, mirroring [ConfigLoader].
 *
 * @param dir the directory holding reports.json (injectable so tests can use a temp dir).
 */
class RunReportStore(
    private val dir: Path = OsUtils.userDataDir(),
    private val maxReports: Int = 500,
) : RunReportRepository {

    private val readJson = Json { ignoreUnknownKeys = true }
    private val writeJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private val file: Path get() = dir.resolve("reports.json")

    override suspend fun loadAll(): List<RunReport> = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext emptyList()
        runCatching { readJson.decodeFromString<List<RunReport>>(Files.readString(file)) }
            .getOrDefault(emptyList())
    }

    override suspend fun append(report: RunReport): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val current = if (Files.exists(file)) {
                runCatching { readJson.decodeFromString<List<RunReport>>(Files.readString(file)) }
                    .getOrDefault(emptyList())
            } else emptyList()
            // Prepend (newest first), then cap. A re-run with the same id replaces the older entry.
            val merged = (listOf(report) + current.filterNot { it.id == report.id }).take(maxReports)
            Files.createDirectories(dir)
            Files.writeString(file, writeJson.encodeToString(merged))
        }
        Unit
    }
}
