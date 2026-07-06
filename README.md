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

## TL;DR

```bash
# 1. Get a JDK 17 (only if you don't already have one — see "Prerequisites")
# 2. Clone this repo (the pipeline is already included, at pipeline/) and run it:
git clone https://github.com/DaChelimo/sam3d.git
cd sam3d
./gradlew :composeApp:run
```

Then, on the in-app **Setup** screen, click **Set up environment** once. It **installs Python**, builds
an isolated environment, installs the pipeline's dependencies, and downloads the SAM model checkpoint
(~2.4 GB) — all with a progress bar, and it resumes if interrupted. **No Python, no conda, no terminal
required** — the app installs everything it needs (via [`uv`](https://github.com/astral-sh/uv)). Each
field is explained on-screen and [below](#7-the-in-app-setup-screen-explained).

---

## Do I need Android Studio?

**No.** Despite being a "Kotlin Multiplatform" project, this app targets **only the JVM desktop**
(`kotlin { jvm() }` — there is no Android target). You never need the Android SDK, an emulator, or
Android Studio to build or run it.

You have three ways to work with it, in increasing convenience:

| Option | What you need | Good for |
| --- | --- | --- |
| **Command line only** | A JDK 17 (see below). The Gradle wrapper (`./gradlew`) is bundled. | Just running/building the app. **No IDE required.** |
| **IntelliJ IDEA** (Community is free) | The IDE + a JDK. | Editing Kotlin, debugging, the nicest experience. |
| **Android Studio** | The IDE + a JDK. | Works fine too (it's IntelliJ underneath) — but it's heavier and brings nothing this project uses. **Not recommended just for this.** |

> **Note about `local.properties`:** you may see a `local.properties` file with an `sdk.dir=...`
> line pointing at an Android SDK. That is a leftover from the Kotlin Multiplatform project template
> and is **not used** by this JVM-only build. You can ignore it (or delete the file entirely) — it
> does not need to point anywhere valid.

---

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

> The engine path is still **configurable in-app** (Setup screen), but the app auto-detects
> `pipeline/` when run from a checkout of this repo, so you normally don't need to set it.

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

The Segment Anything **ViT-H** checkpoint (`pipeline/checkpoints/sam_vit_h_4b8939.pth`, ~2.4 GB) is
downloaded as the **last step of Set up environment** — you don't do anything separately. It lands at
`pipeline/checkpoints/sam_vit_h_4b8939.pth` (gitignored — never committed) and resumes from a partial
file if a download is interrupted. If you'd rather fetch it by hand (e.g. to reuse an existing copy),
run `python pipeline/download_checkpoint.py`; the app detects the file and shows a green ✓ once present.

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
| **Windows** | `%APPDATA%\SAM3D\config.json` |
| **Linux** | `$XDG_CONFIG_HOME/sam3d/config.json` (usually `~/.config/sam3d/config.json`) |

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

- **"Set up environment" fails.**
  Almost always a network hiccup while downloading `uv`, Python, or a package. Click **Retry** — it
  resumes where it left off (`uv` and the checkpoint don't re-download what they already have). The
  full output is saved to a log under the app's data dir (`…/SAM3D/logs/env-setup-*.log`).
- **Setup was interrupted (closed the app / lost network).**
  Just click **Set up environment** again — it resumes: `uv` skips what's installed and the checkpoint
  continues from its partial file.
- **The pipeline starts but fails immediately.**
  Almost always a missing dependency or checkpoint. Re-run **Set up environment** (it repairs the
  venv), and confirm `pipeline/checkpoints/sam_vit_h_4b8939.pth` exists.
- **A Production run is taking hours.**
  That's expected on CPU (≈3–4 hr at 120 slices). Use the **Draft** preset to validate your
  workflow first; switch to Production for the final scaffold (ideally on a GPU machine).
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
