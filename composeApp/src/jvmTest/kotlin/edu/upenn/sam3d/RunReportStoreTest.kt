package edu.upenn.sam3d

import edu.upenn.sam3d.domain.model.RunReport
import edu.upenn.sam3d.domain.model.RunStatus
import edu.upenn.sam3d.domain.model.StageDuration
import edu.upenn.sam3d.process.RunReportStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round-trips run reports through reports.json: newest-first ordering, capping, and replace-by-id. */
class RunReportStoreTest {

    private fun report(id: String) = RunReport(
        id = id,
        startedAtEpochMs = id.hashCode().toLong(),
        startedAtDisplay = "Jun 7, 2026 at 2:35 PM",
        quality = "Draft",
        slices = 8,
        downsampleTargetMaxDim = 256,
        status = RunStatus.COMPLETE,
        stages = listOf(StageDuration("RUNNING_INFERENCE", "Running SAM inference", 785)),
        totalSeconds = 900,
        outputPath = "/out/output.gcode",
    )

    @Test
    fun `empty when nothing stored`() = runBlocking {
        val dir = Files.createTempDirectory("sam3d-reports")
        assertEquals(emptyList(), RunReportStore(dir).loadAll())
    }

    @Test
    fun `append prepends newest-first and round-trips all fields`() = runBlocking {
        val dir = Files.createTempDirectory("sam3d-reports")
        val store = RunReportStore(dir)
        store.append(report("a"))
        store.append(report("b"))
        val all = store.loadAll()
        assertEquals(listOf("b", "a"), all.map { it.id }, "newest first")
        assertEquals(256, all.first().downsampleTargetMaxDim)
        assertEquals("Running SAM inference", all.first().stages.single().label)
        assertEquals(900, all.first().totalSeconds)
    }

    @Test
    fun `caps at maxReports keeping the newest`() = runBlocking {
        val dir = Files.createTempDirectory("sam3d-reports")
        val store = RunReportStore(dir, maxReports = 3)
        listOf("a", "b", "c", "d").forEach { store.append(report(it)) }
        assertEquals(listOf("d", "c", "b"), store.loadAll().map { it.id })
    }

    @Test
    fun `re-appending the same id replaces the older entry`() = runBlocking {
        val dir = Files.createTempDirectory("sam3d-reports")
        val store = RunReportStore(dir)
        store.append(report("a"))
        store.append(report("b"))
        store.append(report("a"))   // a re-run with the same id
        val all = store.loadAll()
        assertEquals(listOf("a", "b"), all.map { it.id })
        assertTrue(all.count { it.id == "a" } == 1, "no duplicate ids")
    }
}
