# Handoff Plan #2 — Phase 6: Polish

> **For the next Claude instance.** Self-contained. Phases 1–5 are complete and committed; the app
> runs DICOM → annotation → G-code end-to-end. This is the polish phase (`SAM3D_DESKTOP_PLAN.md`
> §12 Phase 6) plus cleanups discovered during Phase 5. Project memory (`MEMORY.md`) is auto-loaded.
> Read `SAM3D_DESKTOP_PLAN.md` §5.2, §6.1–6.4, §11, §14 before starting.

## Ground rules (unchanged, non-negotiable)
- Never modify anything under `../SAM3D-GCODE/` (engine is read-only).
- `commonMain` contains zero `java.*` — JVM code lives in `jvmMain`.
- Python env is ALWAYS `/opt/anaconda3/envs/sam3d/bin/python` (base/`python3` lacks deps).
- Long pipeline runs (>10 min) must be launched detached (`nohup … & disown`) — the tool's
  background tasks are killed at a 10-min cap. `-s 8` for dev (~15–21 min), `-s 120` for real.
- Each pipeline run: `printf 'done\n' | … sam3d.py … --reprompt 0 --reslice 0` and **never `-v`**
  (see memory `project_sam3d.md` for the full why).
- `./gradlew :composeApp:jvmTest` must stay green (64 tests at handoff).

## Tasks (suggested priority order)

### 1. Window-size persistence (config) — small, high value
`UserConfig` and `ConfigLoader` already exist (§11.3 done in the config commit). Add `windowWidth`,
`windowHeight` (Int?, nullable) to `UserConfig`; have `main.kt` seed `rememberWindowState` from them
(fallback 1280×800); persist on change. `ConfigLoader` currently only reads — add a `save(UserConfig)`
that writes `<userDataDir>/SAM3D/config.json` (pretty JSON), and call it on window resize/close.
- Files: `domain/model/UserConfig.kt`, `ConfigLoader.kt` (add `save`), `main.kt`.
- Acceptance: resize window, restart, size restored. Don't clobber the user's path/env keys when
  saving (round-trip the whole `UserConfig`).

### 2. Checkpoint download bar — medium
The Start screen's "Download checkpoint" button dispatches `WizardIntent.DownloadCheckpoint`, which
is currently a no-op in `WizardViewModel`. Wire it: spawn `<pythonExe> download_checkpoint.py` from
`sam3dGcodeDir` (the script exists — `sam3d.py` imports `ensure_checkpoint` from it), parse its tqdm
percentage, and drive a `CheckpointDownloadBar` (per §4 / §6.x). Reuse the tqdm regex from
`StdoutProgressParser`. The checkpoint is 2.4 GB → show % + allow cancel.
- Files: new `process/CheckpointDownloader.kt` (or extend `PythonProcessManager`), new
  `ui/components/CheckpointDownloadBar.kt`, VM intent handling, `StartScreen` wiring.
- Acceptance: with the checkpoint absent, click Download → live %, then the row flips to the green
  "found" state. (To test without deleting the real 2.4 GB file, temporarily point `sam3dGcodeDir`
  at a scratch dir.)

### 3. Pipeline timeout + OOM guard (§14 risk mitigations) — small
- `PythonProcessManager.start()` has no timeout; §14 wants `withTimeout(AppConfig.PIPELINE_TIMEOUT_MS)`
  (the const exists, 30 min) → on timeout, `cancel()` + emit ERROR.
- `Dcm4cheLoader.loadSeries()` has no `MAX_CUBE_BYTES` guard (const exists, 2 GB); §14 wants a check
  with a clear error if `cubeSize^3` exceeds it.
- Files: `process/PythonProcessManager.kt`, `dicom/Dcm4cheLoader.kt`. Add focused tests.

### 4. Error dialog depth + open-log — small
Phase 5's error dialog (`ProcessingScreen`) shows the last 20 stdout lines. Add an "Open log" button
— `PythonProcessManager` writes a per-run log to `<userDataDir>/SAM3D/logs/sam3d-<ts>.log`; expose
that path (and add `OsUtils.openFile`/reveal). Optionally tailor the message by failure type.
- Files: `PythonProcessManager.kt` (expose log path), `OsUtils.kt`, `ProcessingScreen.kt`.

### 5. Loading shimmer — low
`PromptingScreen` shows a `CircularProgressIndicator` while a slice decodes and while the cube loads.
Replace with a proper shimmer placeholder for polish. Files: new `ui/components/Shimmer.kt`,
`PromptingScreen.kt`. Cosmetic.

### 6. Small cleanups discovered in Phase 5 — small
- **Elapsed ticker** in `ProcessingScreen` keeps counting behind the error/Done dialog (it showed
  44:23 after an early crash). Stop it on terminal stages — e.g. gate the `while(true){delay}` loop
  on `state.error == null && stage !in {COMPLETE, ERROR}` via `rememberUpdatedState`/keying.
- Optionally **config-drive `slices`** (currently `AppConfig.PipelineDefaults.SLICES = 120`, a const)
  so dev can flip 8↔120 via `config.json` instead of editing source. Add `slices` to `UserConfig`,
  an `AppConfig.slices` accessor, and use it in `PythonProcessManager.buildCommand`.

### 7. CI skeleton — medium, with a CAVEAT
Add `.github/workflows/ci.yml`: matrix (macOS/Windows/Linux), `./gradlew :composeApp:jvmTest` on
push/PR, and `packageDmg/Msi/Deb` on tag.
- **CAVEAT — some tests are machine-specific and WILL fail in CI as-is:**
  - `Dcm4cheLoaderTest` loads `/Users/DaChelimo/Documents/Research/Sample-Data/00000304` (absent in CI).
  - `PromptingEndToEndValidationTest` writes under `composeApp/build/` and assumes paths.
  - `UserConfigTest`/`SaveAnnotationsUseCaseTest`/etc. are pure and CI-safe.
  Before enabling CI, tag the data-dependent tests (e.g. a JUnit `@Tag("integration")` /
  category) and exclude them from the CI task, or commit a tiny synthetic DICOM fixture. Decide and
  document. Don't let CI go red on machine-specific tests.

### 8. Packaging / notarization notes — defer to release
`./gradlew :composeApp:packageDmg` works locally. Document macOS codesign + notarization steps and
add icons (`nativeDistributions` currently has no icon files set). Defer actual signing to release.

## Key references
- `SAM3D_DESKTOP_PLAN.md` §12 (Phase 6 list), §6.1–6.4, §11 (build/config/packaging), §14 (risks).
- `composeApp/.../AppConfig.kt`, `ConfigLoader.kt`, `domain/model/UserConfig.kt` (config plumbing from #3 of the session).
- `composeApp/.../process/PythonProcessManager.kt` (subprocess, log file, tqdm), `StdoutProgressParser.kt`.
- `composeApp/.../ui/wizard/{StartScreen,ProcessingScreen}.kt`, `ui/components/` (where new components go).
- Memory: `project_sam3d.md` (operational knowledge, env, gotchas), `project_architecture.md`.

## Operational cheat-sheet
- Build/test: `./gradlew :composeApp:jvmTest`. Run app: `./gradlew :composeApp:run` (launch detached
  for long-lived observation; the tool kills tracked bg tasks at 10 min).
- The app prefills Start-screen fields from `~/Library/Application Support/SAM3D/config.json`.
- Test DICOM: `/Users/DaChelimo/Documents/Research/Sample-Data/00000304` (S=562). Output dir the user
  uses: `/Users/DaChelimo/Documents/Research/OUTPUT`.
- 64 tests green at handoff; keep them green and add tests for new logic.
