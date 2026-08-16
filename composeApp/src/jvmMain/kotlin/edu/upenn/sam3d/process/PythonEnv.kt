package edu.upenn.sam3d.process

/**
 * The environment every Python subprocess the app spawns must run with.
 *
 * ### Why this exists
 * When stdout is a pipe — which it always is here, since we read it — Windows CPython (≤3.14) encodes
 * it with the ANSI code page, cp1252 on a US install, *not* UTF-8. Any non-ASCII byte the engine or a
 * dependency prints then kills the process with `UnicodeEncodeError: 'charmap' codec can't encode
 * character …`. That is not hypothetical: `sam3d.py:112` prints `f"Using device →  {device}"`, and
 * U+2192 has no cp1252 mapping, so every lab run died there — right after `parse_prompts`, seconds
 * before SAM inference would have started. macOS never saw it because its locale is already UTF-8,
 * and neither did anyone running `sam3d.py` by hand, because CPython uses the Unicode console API for
 * a real console and only falls back to the code page for pipes.
 *
 * `PYTHONUTF8=1` additionally makes `open()` default to UTF-8, which covers the engine's unencoded
 * file I/O — `scale_transform.py:125` reading `points.json`, `Voxels2GCode.py:196` writing the G-code
 * — against the same code-page mismatch.
 *
 * ### Why an env var rather than fixing the print
 * `pipeline/` is vendored read-only (see CLAUDE.md), so we cannot touch `sam3d.py`. But even if we
 * could, patching the one arrow would leave the class of bug open: a DICOM field with an accent, a
 * non-ASCII install path, a library deprecation warning with a curly quote. The env var closes all of
 * them at once.
 *
 * ### PYTHONUNBUFFERED
 * Unrelated to the crash. Block-buffered stdout holds `print()` output in an 8 KB buffer, which
 * starves [StdoutProgressParser] of the stage transitions that drive the progress UI, and reorders
 * logs so a traceback lands before the lines explaining it. tqdm writes unbuffered to stderr, which
 * is why the progress bar looked live while the stage labels lagged behind it.
 *
 * Apply this to any subprocess whose output we stream. The two `--version` probes
 * ([EnvironmentSetupManager.venvValid] and the Start screen's) deliberately skip it: their output is
 * a fixed ASCII string, and they discard it rather than parsing it.
 */
object PythonEnv {

    /** Adds the required variables to a [ProcessBuilder.environment] map, overwriting any inherited. */
    fun apply(env: MutableMap<String, String>) {
        env["PYTHONIOENCODING"] = "utf-8"   // stdout/stderr — the crash above
        env["PYTHONUTF8"] = "1"             // open() defaults, argv, filesystem encoding
        env["PYTHONUNBUFFERED"] = "1"       // live progress parsing + correctly ordered logs
    }
}
