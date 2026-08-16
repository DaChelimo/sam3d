package edu.upenn.sam3d

import edu.upenn.sam3d.process.CheckpointDownloader
import edu.upenn.sam3d.state.EnvSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure, CI-safe coverage for the environment-setup UI model and checkpoint resume helper. */
class EnvSetupTest {

    @Test
    fun `checkpoint fraction is received over total when known`() {
        val s = EnvSetup.DownloadingCheckpoint(receivedBytes = 1_200_000_000, totalBytes = 2_400_000_000)
        assertEquals(0.5f, s.fraction)
    }

    @Test
    fun `checkpoint fraction is null without a total and clamps to one`() {
        assertNull(EnvSetup.DownloadingCheckpoint(1_000, null).fraction)
        assertEquals(1f, EnvSetup.DownloadingCheckpoint(3_000, 2_000).fraction)
    }

    @Test
    fun `isActive is true for running stages only`() {
        assertTrue(EnvSetup.PreparingUv.isActive)
        assertTrue(EnvSetup.InstallingPython.isActive)
        assertTrue(EnvSetup.CreatingVenv.isActive)
        assertTrue(EnvSetup.InstallingDeps("torch").isActive)
        assertTrue(EnvSetup.DownloadingCheckpoint(1, 2).isActive)
        assertTrue(EnvSetup.Verifying.isActive)
    }

    @Test
    fun `isActive is false for idle and terminal states`() {
        assertFalse(EnvSetup.Idle.isActive)
        assertFalse(EnvSetup.Succeeded.isActive)
        assertFalse(EnvSetup.Failed(EnvSetup.Stage.INSTALL, "boom").isActive)
    }

    @Test
    fun `content-range total is parsed from the header`() {
        assertEquals(1024L, CheckpointDownloader.parseContentRangeTotal("bytes 200-1023/1024"))
        assertEquals(2_400_000_000L, CheckpointDownloader.parseContentRangeTotal("bytes 0-99/2400000000"))
    }

    @Test
    fun `content-range total is null when absent or unknown`() {
        assertNull(CheckpointDownloader.parseContentRangeTotal(null))
        assertNull(CheckpointDownloader.parseContentRangeTotal("bytes 0-99/*"))
    }
}
