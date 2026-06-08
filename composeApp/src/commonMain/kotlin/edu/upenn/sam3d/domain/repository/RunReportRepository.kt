package edu.upenn.sam3d.domain.repository

import edu.upenn.sam3d.domain.model.RunReport

/**
 * Port (commonMain) for persisting and reading back per-run timing reports. Implemented in jvmMain
 * (`RunReportStore`, writing `<userDataDir>/SAM3D/reports.json`) so commonMain stays free of java.*
 * (Critical rule #4). Injected into the WizardViewModel; null in tests/previews = don't record.
 */
interface RunReportRepository {
    /** All recorded runs, newest first. Empty (never throws) if nothing is stored yet. */
    suspend fun loadAll(): List<RunReport>

    /** Append one run (prepended so newest is first), best-effort. */
    suspend fun append(report: RunReport)
}
