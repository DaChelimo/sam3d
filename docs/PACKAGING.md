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
inside `SAM3D/runtime/`.

**Build constraint:** `createDistributable` is platform-native, so the *Windows* app image can only
be produced on Windows. From a Mac, build it via CI:

- Push a `v*` tag → the portable zip is attached to the GitHub Release as `SAM3D-windows-portable.zip`.
- Or trigger **Actions → CI → Run workflow** (`workflow_dispatch`) → download the
  `sam3d-windows-portable` artifact from that run (no tag needed).

See `.github/workflows/ci.yml`, job `package-portable-windows`.

**Caveat — application whitelisting:** if IT blocks `java.exe` by signature/path wherever it lives,
the bundled `runtime/bin/java.exe` can still be blocked. The bundled JRE removes the *install*
requirement, not an active execution ban. Test on a real lab machine to find out which you're facing.

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
