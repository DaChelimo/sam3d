package edu.upenn.sam3d.process

import edu.upenn.sam3d.OsUtils

/**
 * Prevents the machine from *idle*-sleeping while a pipeline run is active (Phase 6 / sleep handling
 * #1), so a user who walks away mid-run comes back to a finished run instead of a frozen one. The
 * **display** is still allowed to sleep — only system sleep is blocked.
 *
 * Per-OS, using only built-in tooling (no native dependency); each is best-effort and a no-op if the
 * mechanism is unavailable:
 *  - **macOS**: `caffeinate -i -w <pid>` — blocks idle system sleep, and auto-exits if our JVM dies.
 *  - **Windows**: a PowerShell holder that P/Invokes `SetThreadExecutionState(ES_CONTINUOUS |
 *    ES_SYSTEM_REQUIRED)`; killing it releases the assertion.
 *  - **Linux**: `systemd-inhibit --what=sleep:idle … sleep infinity` (systemd desktops).
 *
 * Caveat (documented for the UI/user): `caffeinate -i` does not stop **lid-close** sleep on battery —
 * for multi-hour Production runs, stay on AC power or keep the lid open.
 */
class SystemSleepInhibitor {

    @Volatile private var holder: Process? = null

    @Synchronized
    fun acquire() {
        if (holder?.isAlive == true) return
        val cmd = command() ?: return
        holder = runCatching {
            ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrNull()
    }

    @Synchronized
    fun release() {
        holder?.let { runCatching { it.destroyForcibly() } }
        holder = null
    }

    private fun command(): List<String>? = when {
        OsUtils.isMac() -> {
            val pid = ProcessHandle.current().pid()
            listOf("caffeinate", "-i", "-w", pid.toString())
        }
        OsUtils.isWindows() -> listOf("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", WINDOWS_KEEP_AWAKE)
        else -> listOf("systemd-inhibit", "--what=sleep:idle", "--who=SAM3D", "--why=Pipeline running", "--mode=block", "sleep", "infinity")
    }

    private companion object {
        // Holds ES_CONTINUOUS|ES_SYSTEM_REQUIRED on this thread until the process is killed.
        const val WINDOWS_KEEP_AWAKE =
            "\$s='[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint e);';" +
                "\$p=Add-Type -MemberDefinition \$s -Name Pwr -Namespace Win32 -PassThru;" +
                "\$p::SetThreadExecutionState(0x80000000 -bor 0x00000001) | Out-Null;" +
                "while(\$true){Start-Sleep -Seconds 60}"
    }
}
