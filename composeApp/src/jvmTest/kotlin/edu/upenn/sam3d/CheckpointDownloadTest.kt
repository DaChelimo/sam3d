package edu.upenn.sam3d

import edu.upenn.sam3d.state.CheckpointDownload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure, CI-safe coverage for the checkpoint-download UI model (Phase 6 task 4). */
class CheckpointDownloadTest {

    @Test
    fun `fraction is received over total when total is known`() {
        val s = CheckpointDownload.InProgress(receivedBytes = 1_200_000_000, totalBytes = 2_400_000_000)
        assertEquals(0.5f, s.fraction)
    }

    @Test
    fun `fraction is null when total is unknown (no Content-Length)`() {
        assertNull(CheckpointDownload.InProgress(receivedBytes = 1_000, totalBytes = null).fraction)
    }

    @Test
    fun `fraction is clamped to one`() {
        val s = CheckpointDownload.InProgress(receivedBytes = 3_000, totalBytes = 2_000)
        assertEquals(1f, s.fraction)
    }

    @Test
    fun `isActive is true only while connecting or in progress`() {
        assertTrue(CheckpointDownload.Connecting.isActive)
        assertTrue(CheckpointDownload.InProgress(1, 2).isActive)
        assertFalse(CheckpointDownload.Idle.isActive)
        assertFalse(CheckpointDownload.Succeeded.isActive)
        assertFalse(CheckpointDownload.Failed("x").isActive)
    }
}
