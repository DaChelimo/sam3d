package edu.upenn.sam3d

import edu.upenn.sam3d.state.FailureHints
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure, CI-safe coverage for the error-dialog failure classifier (Phase 6 task 4). */
class FailureHintsTest {

    @Test
    fun `CUDA OOM is recognised`() {
        val hint = FailureHints.classify("RuntimeError: CUDA out of memory. Tried to allocate 2.00 GiB")
        assertTrue(hint != null && hint.contains("memory", ignoreCase = true))
    }

    @Test
    fun `missing checkpoint is recognised`() {
        val hint = FailureHints.classify("FileNotFoundError: checkpoints/sam_vit_h_4b8939.pth")
        assertTrue(hint != null && hint.contains("checkpoint", ignoreCase = true))
    }

    @Test
    fun `missing python module is recognised`() {
        val hint = FailureHints.classify("ModuleNotFoundError: No module named 'segment_anything'")
        assertTrue(hint != null && hint.contains("dependency", ignoreCase = true))
    }

    @Test
    fun `inactivity stop note is recognised`() {
        val hint = FailureHints.classify("No new output for 20 minutes — the run was stopped (it appears stuck).")
        assertTrue(hint != null && hint.contains("stuck", ignoreCase = true))
    }

    @Test
    fun `unrecognised output yields no hint`() {
        assertNull(FailureHints.classify("line 18\nline 19\nline 20"))
    }

    @Test
    fun `exit code 137 with no recognisable text is diagnosed as an OS-level kill`() {
        val hint = FailureHints.classify("line 18\nline 19\nline 20", exitCode = 137)
        assertTrue(hint != null && hint.contains("memory", ignoreCase = true))
    }

    @Test
    fun `exit code 137 does not override a more specific text match`() {
        // Our own inactivity-timeout kill also exits via SIGKILL (137) — its own precise message
        // must win over the generic OS-kill fallback.
        val hint = FailureHints.classify("No new output for 20 minutes — the run was stopped.", exitCode = 137)
        assertTrue(hint != null && hint.contains("stuck", ignoreCase = true))
    }

    @Test
    fun `no exit code and no recognisable text still yields no hint`() {
        assertNull(FailureHints.classify("line 18\nline 19\nline 20", exitCode = null))
    }
}
