# SAM3D Desktop

A **Kotlin Multiplatform / Compose Desktop** application that wraps the
[SAM3D-GCODE](https://github.com/kpatel3-upenn/SAM3D-GCODE) Python research pipeline behind a
friendly, step-by-step wizard. You load a DICOM scan, drop a few annotation points on the slices,
and the app drives the Python engine to produce a 3D-printable **G-code** scaffold — all running
locally on your machine.

The desktop app **does not contain** the segmentation/G-code logic itself — it's a UI +
orchestration layer that launches the pipeline as a command-line subprocess. The pipeline source is
**vendored in this repo** at [`pipeline/`](pipeline/), so a single clone gets you everything, and the
app builds its own Python environment on first launch (one click — no Python to install yourself).

This README walks you through it, end to end.

---

## Just want to use it? Download it.

**You do not need to clone this repository, install Java, install Python, or use a terminal.** Grab
the build for your machine from the [Releases page](https://github.com/DaChelimo/sam3d/releases) —
or, for the newest build at a link that never changes,
[**latest-build**](https://github.com/DaChelimo/sam3d/releases/tag/latest-build):

| Platform | File | How to run it |
| --- | --- | --- |
| **Windows (recommended)** | `SAM3D-windows-portable.zip` | Unzip it somewhere you can write to, then run `SAM3D\SAM3D.exe`. No installer, no admin rights. |
| Windows (installer) | `.msi` | Double-click. Needs admin rights. |
| macOS | `.dmg` | Open it and drag SAM3D to Applications. |
| Linux | `.deb` | `sudo dpkg -i sam3d_*.deb` |

Every download bundles both the Java runtime **and** the Python pipeline engine. On first launch,
click **Set up environment** once: it installs a private Python, the engine's dependencies, and the
~2.4 GB SAM model checkpoint into your own user folder, with a progress bar, resuming if interrupted.

Budget **~8 GB of free disk** and a connection that can reach `github.com` and
`dl.fbaipublicfiles.com` (some university networks block one or both).

> **Don't skip "Set up environment".** The app opens and lets you pick folders before the engine's
> environment exists — but it can't run a scan until that one-time setup finishes. You never need to
> install Python, Git, or anything from a `requirements.txt` yourself; if you find yourself hunting
> for one, something is wrong and the setup log will say what.
>
> Two smaller Windows notes: unzip the folder before running it (Windows opens `.zip` files
> read-only, so the app can't save anything from inside one), and if you see a blue "Windows
> protected your PC" box, that's just the app being unsigned — **More info → Run anyway**.

The rest of this README is for **building from source** — you only need it if you're developing the
app itself.

---

## TL;DR (building from source)

```bash
# 1. Get a JDK 17 (only if you don't already have one — see "Prerequisites")
# 2. Clone this repo (the pipeline is already included, at pipeline/) and run it:
git clone https://github.com/DaChelimo/sam3d.git
cd sam3d
./gradlew :composeApp:run          # gradlew.bat on Windows
```

Then, on the in-app **Setup** screen, click **Set up environment** once. It **installs Python**, builds
an isolated environment, installs the pipeline's dependencies, and downloads the SAM model checkpoint
(~2.4 GB) — all with a progress bar, and it resumes if interrupted. **No Python, no conda, no terminal
required** — the app installs everything it needs (via [`uv`](https://github.com/astral-sh/uv)). Each
field is explained on-screen and [below](#7-the-in-app-setup-screen-explained).


## Prerequisites

### For the desktop app

- **JDK 17** (or newer). Compose Desktop requires JDK 17+.
  - Check what you have: `java -version`
  - If you don't have one, install via [Temurin / Adoptium](https://adoptium.net/) (pick **17 LTS**),
    or `brew install --cask temurin@17` on macOS, or `sdk install java 17.0.12-tem` with
    [SDKMAN!](https://sdkman.io/).
  - You do **not** need to install Gradle — the project ships a Gradle wrapper (`gradlew`) that
    downloads the correct version (8.14.3) automatically on first run.
- **Git** — to clone the repo.

### For the Python engine

- **Nothing to install by hand.** You do **not** need Python, conda, or pip. The app's **Set up
  environment** button downloads [`uv`](https://github.com/astral-sh/uv) (a small, self-contained
  tool), which installs a managed **Python 3.11** and builds an isolated environment for the pipeline.
  All you need is an internet connection the first time.
- **~5 GB free disk** — ~2.4 GB for the SAM model checkpoint plus Python + the dependencies (PyTorch
  is large).
- A **GPU** (NVIDIA/CUDA) makes inference much faster but is **not required**. On a CPU, use the
  **Draft** quality preset (fewer slices) so a run finishes in a reasonable time.

---

## Folder layout

The Python engine lives inside this repo, at `pipeline/`:

```
sam3d/
├── composeApp/       ← the desktop app (you build & run this)
└── pipeline/         ← the Python engine (vendored, never modified — see CLAUDE.md)
```

**How the app finds the engine**, in order:

1. A `pipeline/` folder above the working directory — i.e. you're running from a checkout. Used in
   place, so the checkpoint stays at `pipeline/checkpoints/` and nothing about the dev loop changes.
2. The copy bundled inside the installed app, which is staged out to a writable per-user folder
   (`…/SAM3D/engine`) on first launch. Installed apps live in read-only locations like
   `C:\Program Files`, and the engine needs to write there.
3. Whatever `sam3dGcodeDir` in `config.json` points at, if you set it by hand.

If all three come up empty the Setup screen shows a **Pipeline engine folder** picker rather than
blocking — but that only happens with a damaged install.

> ⚠️ `pipeline/docs/CROSS_PLATFORM.md` is inherited from the upstream research repo and describes an
> **older Electron-based** version of this project (`conda activate`, `npm install`,
> `sam3d-backend.exe`). None of it applies here. It's inside the read-only vendored tree, so it can't
> be deleted — ignore it and use this README.

---

## Setup, step by step

### 1. Clone the repository

```bash
cd ~/your-projects        # or wherever you keep code
git clone https://github.com/DaChelimo/sam3d.git
cd sam3d
```

The Python engine is already there, at `sam3d/pipeline/`.

### 2. Install a JDK 17 (if needed)

```bash
java -version          # need 17 or newer
```

If the version is below 17 (or `java` isn't found), install one — see
[Prerequisites](#for-the-desktop-app). On macOS the project is known to work with the bundled
JetBrains Runtime 17 as well.

### 3. Set up the Python environment — one click, in the app

**You don't install anything by hand.** On the app's **Setup** screen there's a **Set up environment**
button. Click it once and the app:

1. downloads [`uv`](https://github.com/astral-sh/uv) — a small, self-contained tool from Astral,
2. uses it to **install a managed Python 3.11** (no system Python required),
3. builds an **isolated virtual environment** for the pipeline (kept in the app's data folder, so the
   `pipeline/` source stays untouched),
4. installs all the engine's dependencies (PyTorch, OpenCV, pydicom, segment-anything, …), and
5. downloads the **SAM model checkpoint** (~2.4 GB).

The whole thing shows a live progress bar, can be **canceled**, and — importantly — **resumes if
interrupted**: quit mid-install and relaunch, click again, and it picks up where it left off (uv skips
what's already installed; the checkpoint continues from the partial file). When it finishes,
**Continue** unlocks. There's nothing else to configure.

> **Time & size:** the first run installs Python and pulls in PyTorch + Segment Anything, so expect
> several minutes and a few GB of download. It's a one-time cost. `uv` makes the install substantially
> faster than a plain `pip install`.

#### What gets installed (the engine's dependencies)

The setup installs everything from [`pipeline/requirements.txt`](pipeline/requirements.txt) — here is
exactly what the pipeline imports and why, so you know what a working environment contains:

| Package (PyPI) | Imported as | What the pipeline uses it for |
| --- | --- | --- |
| `numpy` | `numpy` | Core array / volume math — used throughout |
| `scipy` | `scipy` | Spatial math and `gaussian_kde` density estimation |
| `matplotlib` | `matplotlib` | Colormaps and plotting helpers |
| `opencv-python` | `cv2` | Image operations during slice / prompt transforms |
| `torch` | `torch` | Runs the SAM neural-network inference |
| `torchvision`, `torchaudio` | *(transitive)* | Companions to `torch` required by Segment Anything |
| `segment-anything` *(from GitHub)* | `segment_anything` | The SAM model itself (`sam_model_registry`, `SamPredictor`) |
| `pydicom` | `pydicom` | Reads your `.dcm` DICOM slices |
| `nibabel` | `nibabel` | NIfTI medical-volume I/O |
| `SimpleITK` | `SimpleITK` | Medical-image I/O / resampling |
| `Pillow` | `PIL` | Image loading / saving |
| `mrcfile` | `mrcfile` | MRC volume-format I/O |
| `open3d` | `open3d` | Point-cloud → mesh reconstruction |
| `fastkde` | `fastkde` | Fast kernel density estimation for variable-density regions |
| `tqdm` | `tqdm` | Progress bars (the app parses these to drive its progress UI) |

`requirements.txt` also lists three entries the **desktop app's pipeline does not need** — they're
safe to leave installed, but worth understanding:

- **`flask`** — only the engine's optional standalone web backend. The desktop app talks to the
  engine over the **CLI**, not HTTP, so Flask is never used on this path.
- **`pyinstaller`** — a build-only tool for packaging the engine into a standalone executable; not
  used at runtime.
- **`tkinter`** — Python's built-in GUI toolkit, used only by the engine's interactive re-prompting
  window, which the app deliberately **bypasses** (it runs `sam3d.py` with `--reprompt 0`). It ships
  with most Python installs and needs no `pip install`.

> **GPU note:** setup installs a default PyTorch build. For NVIDIA/CUDA acceleration you may want a
> CUDA-specific wheel from [pytorch.org](https://pytorch.org/get-started/locally/) — install it into
> the app's venv afterward (`…/SAM3D/venv`) with that venv's `pip`. It's optional; the pipeline runs on
> CPU too (use the **Draft** quality preset so a run finishes quickly).

#### The SAM model checkpoint (~2.4 GB)

The Segment Anything **ViT-H** checkpoint (`sam_vit_h_4b8939.pth`, ~2.4 GB) is downloaded as the
**last step of Set up environment** — you don't do anything separately. It lands in the engine's
`checkpoints/` folder and resumes from a partial file if a download is interrupted:

- **From a checkout:** `pipeline/checkpoints/` (gitignored — never committed).
- **Installed app:** `…/SAM3D/engine/checkpoints/` in your user data folder. App updates re-stage the
  engine's source files but leave this alone, so upgrading never costs you the download again.

If you'd rather fetch it by hand (e.g. to reuse an existing copy), run
`python pipeline/download_checkpoint.py`, or just drop the file into that `checkpoints/` folder; the
app detects it and shows a green ✓ once present.

### 4. Build & run the desktop app

From the **`sam3d`** directory:

```bash
cd ~/your-projects/sam3d

# Run the app (downloads Gradle + dependencies on first run, then launches the window)
./gradlew :composeApp:run
```

On Windows use `gradlew.bat :composeApp:run`.

The first run will take a while (Gradle and the Compose/Kotlin dependencies download once). When it
finishes, the SAM3D window opens on the **Setup** screen.

### 5. (Optional) Run from an IDE instead

If you prefer an IDE: open the **`sam3d`** folder in **IntelliJ IDEA** (or Android Studio), let it
import the Gradle project, then run the `composeApp` → `run` Gradle task, or run the `main()`
function in
[`composeApp/src/jvmMain/kotlin/edu/upenn/sam3d/main.kt`](composeApp/src/jvmMain/kotlin/edu/upenn/sam3d/main.kt).
Make sure the IDE's Gradle JVM is set to a JDK 17.

---

## 7. The in-app Setup screen, explained

When the app opens, the first screen asks for a couple of folders and offers the one-click
environment setup. Each field has on-screen helper text; here is the full explanation:

| Field | What it is | How to get it |
| --- | --- | --- |
| **DICOM folder** | The folder holding your scan as a series of `.dcm` slice files (one file per slice). | Point it at the **folder** of your CT/MRI series — *not* an individual `.dcm` file. |
| **Output folder** | **An empty folder you create** for the results. The 3D-printable G-code (`output.gcode`) and intermediate files are written here. | Make a **new, empty** folder first (e.g. `mkdir ~/sam3d-output`), then select it. Using a fresh folder keeps results from getting mixed up with other files. |
| **Pipeline quality** | A plain-language stand-in for the engine's slice count (`-s`). **Draft** (8 slices, ~15–20 min) for testing the workflow; **Production** (120 slices, ~3–4 hr) for the final scaffold. | Just click a card. CPU-only? Start with **Draft**. Your choice is remembered for next time. |
| **Set up environment** (button, bottom bar) | Installs Python, builds the environment, installs the dependencies, and downloads the SAM checkpoint (~2.4 GB) in one go. Cancelable; resumes if interrupted. | Click it once and watch the progress bar. No prerequisites beyond an internet connection. Disappears once the environment is ready. |

There's no Python field — the app manages the interpreter itself. Once the environment is ready (deps
installed and the checkpoint downloaded), **Continue** unlocks and you advance through the wizard
(annotate slices → run pipeline → done).

---

## Where settings are stored (`config.json`)

The app remembers settings in a per-user `config.json`. It's seeded from a bundled template on first
launch and lives at:

| OS | Location |
| --- | --- |
| **macOS** | `~/Library/Application Support/SAM3D/config.json` |
| **Windows** | `%LOCALAPPDATA%\SAM3D\config.json` |
| **Linux** | `$XDG_CONFIG_HOME/sam3d/config.json` (usually `~/.config/sam3d/config.json`) |

The same folder holds the Python venv, the staged engine (with its checkpoint), the setup and run
logs, and `reports.json`.

> **Windows: `%LOCALAPPDATA%`, not `%APPDATA%`.** On a domain-joined machine — a lab PC, typically —
> `%APPDATA%` (Roaming) is synchronised to a network profile and often quota'd, so several GB of venv
> and model weights there either fails outright or makes every logon crawl. If you're upgrading from
> a version that used `%APPDATA%\SAM3D`, the app moves it for you on first launch.

You normally never touch this file — the Setup screen manages it. But if you'd rather **pre-fill the
paths** so you don't pick them every session, you can edit it directly. All keys are optional:

```json
{
  "dicomFolderPath": "/Users/you/scans/patient-001",
  "outputFolderPath": "/Users/you/sam3d-output",
  "slices": 8
}
```

> `sam3dGcodeDir` and `pythonPath` are both **optional** and normally omitted — the app auto-detects
> the vendored `pipeline/` directory, and `pythonPath` is written for you (pointing at the venv
> *Set up environment* built) when setup succeeds. Set `pythonPath` by hand only to point at your own
> interpreter. The folder pickers on the Setup screen fill in the other paths for the current session;
> the **quality** choice and window size auto-save. Setting `dicomFolderPath` and `outputFolderPath`
> here makes them **pre-fill on every launch**. (The app also writes a `setupComplete` hint —
> harmless to ignore.)

---

## Common Gradle commands

Run all of these from the `sam3d/` directory (`gradlew.bat` on Windows):

```bash
./gradlew :composeApp:run          # build & launch the app
./gradlew :composeApp:jvmTest      # run all tests
./gradlew :composeApp:packageDmg   # build a macOS .dmg
./gradlew :composeApp:packageMsi   # build a Windows .msi   (run on Windows)
./gradlew :composeApp:packageDeb   # build a Linux .deb     (run on Linux)
```

Packaging produces a self-contained bundle with its own JRE — see
[`docs/PACKAGING.md`](docs/PACKAGING.md) for signing/notarization details. Note that packaging only
bundles the **desktop app**; end users run **Set up environment** on first launch (which installs
Python and the dependencies via `uv`) — they need no pre-installed Python, just an internet connection.

---

## Troubleshooting

- **"Pipeline engine not found" / the Set up button is greyed out.**
  The app couldn't locate its Python engine. Installed builds ship it and a checkout provides it at
  `pipeline/`, so this means a damaged or partial install — reinstall from the
  [Releases page](https://github.com/DaChelimo/sam3d/releases). As a stopgap, the Setup screen shows a
  **Pipeline engine folder** picker: download this repo's source (**Code → Download ZIP**, no Git
  needed), extract it, and point the picker at the `pipeline` folder inside.
- **Windows says "Windows protected your PC" and won't launch it.**
  The build isn't code-signed. Click **More info → Run anyway**. If nothing happens at all when you
  double-click, check you unzipped the folder first — Windows opens zips read-only.
- **"Set up environment" fails.**
  Usually the network. Click **Retry** — it resumes where it left off (`uv` and the checkpoint don't
  re-download what they already have). The full output is saved to a log under the app's data dir
  (`…/SAM3D/logs/env-setup-*.log`); the error message names the stage and, for import failures, the
  package. Two causes worth knowing on a managed network:
  - **Blocked hosts.** Setup needs `github.com` (for `uv` and the SAM source) and
    `dl.fbaipublicfiles.com` (for the checkpoint). Some university networks block one or both.
  - **Antivirus.** Defender occasionally quarantines the freshly downloaded `uv.exe`. The log shows it.
- **Setup fails at "Finishing up" naming a package it can't import.**
  On Windows this is nearly always a missing **Microsoft Visual C++ Redistributable** — `opencv`,
  `open3d`, `torch` and `SimpleITK` ship compiled wheels that need it. Install it from Microsoft and
  hit Retry.
- **"Not enough free disk space".**
  Setup needs ~8 GB (Python, PyTorch, the 2.4 GB checkpoint, and working space). It checks up front so
  you find out in seconds rather than twenty minutes in.
- **Setup was interrupted (closed the app / lost network).**
  Just click **Set up environment** again — it resumes: `uv` skips what's installed and the checkpoint
  continues from its partial file.
- **The pipeline starts but fails immediately.**
  Almost always a missing dependency or checkpoint. Re-run **Set up environment** (it repairs the
  venv), and confirm `pipeline/checkpoints/sam_vit_h_4b8939.pth` exists.
- **A Production run is taking hours.**
  That's expected on CPU — plan for it to run overnight at 120 slices, because PyPI's Windows PyTorch
  wheels are CPU-only and a lab desktop is far slower than a developer laptop. Use the **Draft** preset
  to validate your workflow first; switch to Production for the final scaffold (ideally on a GPU
  machine). The app keeps the computer awake for the duration.
- **A run fails right at the end, the second time you use an output folder.**
  Fixed — the app now clears the engine's `temp/` folder before each run. (The engine cleared it with
  a Unix `rm` command that does nothing on Windows, so `os.makedirs` then failed *after* inference.)
  Still, prefer a fresh output folder per run so results don't mix.
- **A run fails partway through with a file-not-found error deep in the output folder.**
  Your output path is probably too long. Windows caps paths at 260 characters and the pipeline writes
  several folders deep inside the one you pick — use something short like `C:\sam3d-output`. The Setup
  screen warns when the path you chose is risky.
- **`./gradlew` fails with a Java version error.**
  Your default JDK is older than 17. Install a JDK 17 and/or point `JAVA_HOME` at it.
- **Build complains about the Android SDK / `local.properties`.**
  This project has no Android target. Delete `local.properties` (or ignore its `sdk.dir` line) — it
  isn't used.

---

## Project layout (for contributors)

- `composeApp/src/commonMain/` — ViewModels, domain model, `@Serializable` DTOs (no `java.*` APIs).
- `composeApp/src/jvmMain/` — `ProcessBuilder`, dcm4che (DICOM), all UI, file I/O.
- `composeApp/src/jvmTest/` — tests and fixtures.
- `pipeline/` — the vendored Python research engine (never modified — see below).
- [`SAM3D_DESKTOP_PLAN.md`](SAM3D_DESKTOP_PLAN.md) — the design document and single source of truth.
- [`CLAUDE.md`](CLAUDE.md) — critical rules (chief among them: **never modify the `pipeline/`
  engine** — it's the read-only research pipeline, vendored from
  [SAM3D-GCODE](https://github.com/kpatel3-upenn/SAM3D-GCODE)).

---

## License

See [LICENSE](LICENSE).
