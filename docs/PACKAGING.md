# Packaging & Release

How to build SAM3D distributions, regenerate the app icon, and (at release time) codesign +
notarize the macOS build. Signing is **deferred to release prep** — unsigned local builds work for
development and internal testing.

## Build a distribution locally

```bash
./gradlew :composeApp:createDistributable   # unpacked app image (fastest; great for a smoke test)
./gradlew :composeApp:packageDmg            # macOS .dmg
./gradlew :composeApp:packageMsi            # Windows .msi   (run on Windows)
./gradlew :composeApp:packageDeb            # Linux .deb     (run on Linux)
./gradlew :composeApp:run                   # run straight from source
```

Outputs land under `composeApp/build/compose/binaries/main/<format>/`. Each format must be built on
its own OS (jpackage is platform-native).

## What ships inside a distribution

Every packaged build contains **both** runtimes it needs:

```
SAM3D/
  SAM3D.exe                 ← launcher
  runtime/                  ← the bundled JRE (no system Java required)
  app/
    *.jar                   ← the application
    resources/engine/       ← the Python pipeline: *.py + requirements.txt (~190 KB)
```

The engine is put there by the `stageEngineResources` Gradle task, which copies `pipeline/`'s sources
(never `checkpoints/`, `tempdir/` or `__pycache__` — those are runtime state) into
`build/appResources/common/engine`, wired up via `nativeDistributions.appResourcesRootDir`. At
runtime, `EngineStager` copies it out to a writable per-user directory, because an installed app's
resources live somewhere read-only like `C:\Program Files` and the engine writes into its own folder.

**This is load-bearing.** Before it existed, distributions shipped without any engine: users saw
"The bundled pipeline/ folder wasn't found — run the app from the project root", a disabled Setup
button, and a `requirements.txt` that didn't exist on their machine. The staging task asserts its own
output and the Windows CI job re-checks the built image, so an engine-less installer fails the build
rather than reaching a user.

## Portable Windows build (no installer, no admin, no system Java)

For locked-down environments (e.g. lab machines where Java may not be installed *and* installers may
be blocked), ship the **unpacked app image** instead of the `.msi`:

```
composeApp/build/compose/binaries/main/app/SAM3D/
  SAM3D.exe      ← user double-clicks this
  runtime/       ← bundled JRE (loose files; not registered, not on PATH)
  app/           ← application jars
```

Produced by `createDistributable`. Zip the `SAM3D/` folder, hand it over, the user unzips and runs
`SAM3D\SAM3D.exe`. No installer, no admin rights, and no system-wide Java — the JVM lives entirely
inside `SAM3D/runtime/`, and the Python engine inside `SAM3D/app/resources/engine/`.

Copy `docs/portable-windows/READ-ME-FIRST.txt` in next to `SAM3D.exe` before zipping (CI does this
automatically). It covers the three things that trip up first-time users: the SmartScreen prompt on an
unsigned binary, running the app from *inside* the zip (Windows mounts zips read-only, so setup can't
write anything), and where the setup log lives.

**Build constraint:** `createDistributable` is platform-native, so the *Windows* app image can only
be produced on Windows. From a Mac, build it via CI.

## Publishing a download people can actually get

CI builds all four downloads — `.dmg`, `.msi`, `.deb`, and the portable Windows zip — and publishes
them two ways:

| You do | CI produces | Link to hand out |
|--------|-------------|------------------|
| **Actions → CI → Run workflow** | The four downloads, attached to the rolling `latest-build` prerelease (overwritten each time) | `…/releases/tag/latest-build` — stable, always the newest build |
| Push a `v*` tag | The same four, as a normal versioned release marked *latest* | `…/releases/latest` |

Use the manual run for "someone needs a working copy today" — no version bump, no tag, and the link
you gave out last month still points at the current build. Cut a tag when you want a fixed reference
point (a paper, a lab handoff, something you need to reproduce later).

Both paths run the full test suite first, and every installer job asserts the Python engine is inside
the app image before it's packaged — an engine-less installer fails the build rather than reaching a
user. See `.github/workflows/ci.yml`, jobs `package`, `package-portable-windows`, and `release`.

**What is *not* in the download:** the ~2.4 GB SAM checkpoint and the Python dependencies. Those are
fetched by **Set up environment** on first launch. This isn't a choice we can easily reverse —
GitHub caps a single release asset at 2 GB, so the checkpoint alone cannot be attached, and a
pre-built venv would be platform-specific and similarly oversized. First launch needs a network.

**Caveat — application whitelisting:** if IT blocks `java.exe` by signature/path wherever it lives,
the bundled `runtime/bin/java.exe` can still be blocked. The bundled JRE removes the *install*
requirement, not an active execution ban. Test on a real lab machine to find out which you're facing.

**Caveat — code signing.** Nothing here is signed yet, so Windows shows a SmartScreen warning on
first launch and macOS requires a right-click → Open. In practice this has *not* blocked anyone: the
unsigned portable build launched fine on the lab's managed Windows machine and the user clicked
straight through. Worth doing eventually — on a locked-down machine SmartScreen can be enforced
rather than advisory, and then "Run anyway" isn't offered at all — but treat it as a papercut, not a
release blocker, until a machine actually refuses to launch it.

**What has actually broken in the field:** the *engine and its environment*, never the launcher. The
app started and rendered correctly; it had nothing to run. That's what the resource staging above and
the setup pipeline exist to guarantee, and it's where regression testing effort belongs.

**Caveat — endpoint security.** While a run is active the app holds off idle sleep by spawning a
hidden PowerShell that P/Invokes `SetThreadExecutionState` (see `SystemSleepInhibitor`). It's
best-effort and wrapped so it can never fail a run, but some EDR products flag that pattern. Worth
knowing before IT calls about it.

## App icon

The icon is a Carbon-blue squircle with the cube mark, matching the in-app header glyph. Sources and
generator live in `composeApp/icons/`:

| File | Used by |
|------|---------|
| `AppIcon.icns` | macOS (`nativeDistributions.macOS.iconFile`) |
| `AppIcon.ico`  | Windows |
| `AppIcon.png`  | Linux |

Regenerate after editing the mark (needs Pillow — the sam3d env has it):

```bash
/opt/anaconda3/envs/sam3d/bin/python composeApp/icons/generate_icon.py
```

If your Pillow can't write `.icns`, build it from the PNG with macOS `iconutil`:

```bash
mkdir AppIcon.iconset
for s in 16 32 128 256 512; do
  sips -z $s $s   composeApp/icons/AppIcon.png --out AppIcon.iconset/icon_${s}x${s}.png
  sips -z $((s*2)) $((s*2)) composeApp/icons/AppIcon.png --out AppIcon.iconset/icon_${s}x${s}@2x.png
done
iconutil -c icns AppIcon.iconset -o composeApp/icons/AppIcon.icns
```

## macOS codesigning + notarization (release only)

Unsigned `.app`/`.dmg` builds trigger Gatekeeper ("can't be opened because Apple cannot check it").
Distributing outside your machine requires a **Developer ID Application** certificate, signing, and
notarization. Compose Desktop wires all of this through `nativeDistributions.macOS` — add the block
below at release time (keep secrets out of source; use env vars / Keychain):

```kotlin
macOS {
    bundleID = "edu.upenn.sam3d"
    iconFile.set(project.file("icons/AppIcon.icns"))
    signing {
        sign.set(true)
        identity.set("Developer ID Application: Your Name (TEAMID)")
    }
    notarization {
        appleID.set(System.getenv("NOTARY_APPLE_ID"))
        password.set("@keychain:NOTARY_PASSWORD")  // an app-specific password stored in Keychain
        teamID.set(System.getenv("NOTARY_TEAM_ID"))
    }
}
```

Prerequisites:

1. Apple Developer Program membership; a **Developer ID Application** cert in your login Keychain.
2. An **app-specific password** (appleid.apple.com → Sign-In and Security) stored in Keychain:
   `xcrun notarytool store-credentials NOTARY_PASSWORD --apple-id <id> --team-id <TEAMID>`.
3. Build & notarize: `./gradlew :composeApp:notarizeDmg` (Compose runs sign → submit → staple).

Verify the result:

```bash
codesign --verify --deep --strict --verbose=2 "SAM3D.app"
spctl -a -t open --context context:primary-signature -v "SAM3D-1.0.0.dmg"
xcrun stapler validate "SAM3D-1.0.0.dmg"
```

**Gatekeeper on first launch (unsigned dev builds):** right-click → Open, or
`System Settings → Privacy & Security → Open Anyway`. The spawned Python binary may also need to be
allowed there on first run (§14).

### Windows / Linux

- **Windows**: the `.msi` is unsigned. For production, sign with `signtool` using an EV/OV cert
  (not wired here).
- **Linux**: the `.deb` is unsigned; distribute via a repo or direct download.

## CI

`.github/workflows/ci.yml` runs `:composeApp:jvmTest` on every push/PR across macOS/Windows/Linux,
and builds the per-OS installer on a `v*` tag (uploaded as a workflow artifact). Signing/notarization
is **not** wired into CI yet — it needs the secrets above; add it when release infrastructure exists.
