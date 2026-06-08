# SAM3D Desktop

A **Kotlin Multiplatform / Compose Desktop** application that wraps the
[SAM3D-GCODE](https://github.com/kpatel3-upenn/SAM3D-GCODE) Python research pipeline behind a
friendly, step-by-step wizard. You load a DICOM scan, drop a few annotation points on the slices,
and the app drives the Python engine to produce a 3D-printable **G-code** scaffold — all running
locally on your machine.

The desktop app **does not contain** the segmentation/G-code logic. It is a UI + orchestration layer
that launches the SAM3D-GCODE engine as a command-line subprocess. So setup has **two halves**:

1. **The desktop app** (this repo) — a JVM/Gradle project you build and run.
2. **The Python engine** (a separate repo) — cloned alongside this app and given its own Python
   environment.

This README walks you through both, end to end.

---

## TL;DR

```bash
# 1. Get a JDK 17 (only if you don't already have one — see "Prerequisites")
# 2. Clone BOTH repos as siblings:
git clone https://github.com/DaChelimo/sam3d.git
git clone https://github.com/kpatel3-upenn/SAM3D-GCODE.git

# 3. Set up the Python engine (one-time)
cd SAM3D-GCODE
conda create -n SAM3D_GCODE python=3.11 -y
conda activate SAM3D_GCODE
pip install -r requirements.txt

# 4. Build & run the desktop app
cd ../sam3d
./gradlew :composeApp:run
```

Then follow the in-app **Setup** screen (each field is explained both on-screen and
[below](#7-the-in-app-setup-screen-explained)).

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
- **Git** — to clone the repos.

### For the Python engine

- **Python 3.10 or 3.11** (3.11 recommended).
- **Conda / Miniconda** — strongly recommended for an isolated environment.
- **~5 GB free disk** — ~2.4 GB for the SAM model checkpoint plus the Python dependencies (PyTorch
  is large).
- A **GPU** (NVIDIA/CUDA) makes inference much faster but is **not required**. On a CPU, use the
  **Draft** quality preset (fewer slices) so a run finishes in a reasonable time.

---

## Recommended folder layout

The desktop app expects to find the Python engine in a sibling directory. The cleanest setup is to
clone both repos into the same parent folder:

```
your-projects/
├── sam3d/            ← this desktop app (you build & run this)
└── SAM3D-GCODE/      ← the Python engine (cloned separately, never modified)
```

> The engine path is **configurable in-app**, so it does *not* strictly have to be a sibling — but
> using this layout means the app can often pre-fill the path for you, and it matches every example
> in this guide.

---

## Setup, step by step

### 1. Clone both repositories

```bash
cd ~/your-projects        # or wherever you keep code
git clone https://github.com/DaChelimo/sam3d.git
git clone https://github.com/kpatel3-upenn/SAM3D-GCODE.git
```

You should now have `your-projects/sam3d/` and `your-projects/SAM3D-GCODE/` next to each other.

### 2. Install a JDK 17 (if needed)

```bash
java -version          # need 17 or newer
```

If the version is below 17 (or `java` isn't found), install one — see
[Prerequisites](#for-the-desktop-app). On macOS the project is known to work with the bundled
JetBrains Runtime 17 as well.

### 3. Set up the Python engine

This is the part most people miss — **the app cannot run the pipeline until the engine has a working
Python environment.**

```bash
cd ~/your-projects/SAM3D-GCODE

# Create an isolated environment (named SAM3D_GCODE throughout this guide)
conda create -n SAM3D_GCODE python=3.11 -y
conda activate SAM3D_GCODE

# Install the engine's Python dependencies (PyTorch, OpenCV, pydicom, segment-anything, …)
pip install -r requirements.txt
```

This step pulls in PyTorch and the Segment Anything package, so it can take several minutes and a
few GB of download.

> **Why this matters:** the Python interpreter you select in the app **must be the one that has these
> packages installed.** The pipeline imports them directly at startup, so a plain system `python3`
> (without them) will fail the moment a run begins. Always point the app at the interpreter from the
> environment you ran `pip install -r requirements.txt` in.

#### What gets installed (the engine's dependencies)

`pip install -r requirements.txt` installs everything for you — but here is exactly what the
pipeline imports and why, so you know what a working environment contains:

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

> **GPU note:** `pip install -r requirements.txt` installs a default PyTorch build. For NVIDIA/CUDA
> acceleration you may instead want a CUDA-specific wheel from
> [pytorch.org](https://pytorch.org/get-started/locally/). It's optional — the pipeline runs on CPU
> too (use the **Draft** quality preset so a run finishes quickly).

**Verify the interpreter path** — you'll paste this into the app later:

```bash
# With the environment still activated:
which python      # macOS / Linux  → e.g. /opt/anaconda3/envs/SAM3D_GCODE/bin/python
where python      # Windows         → e.g. C:\Users\you\anaconda3\envs\SAM3D_GCODE\python.exe
```

Keep that path handy.

#### The SAM model checkpoint (~2.4 GB)

The engine needs the Segment Anything **ViT-H** checkpoint
(`checkpoints/sam_vit_h_4b8939.pth`, ~2.4 GB) before it can run inference. You have **two easy
ways** to get it:

- **From inside the app (recommended):** on the Setup screen, after you select the SAM3D-GCODE
  directory, a **"Download checkpoint"** button appears with a live progress bar. Click it once and
  the app streams the file to the right place.
- **From the command line:**
  ```bash
  cd ~/your-projects/SAM3D-GCODE
  conda activate SAM3D_GCODE
  python download_checkpoint.py
  ```

Either way the file lands at `SAM3D-GCODE/checkpoints/sam_vit_h_4b8939.pth`. The app detects it
automatically and shows a green ✓ once present.

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

When the app opens, the first screen asks for a handful of paths. Each field now has on-screen
helper text, but here is the full explanation of what each one wants and how to get it:

| Field | What it is | How to get it |
| --- | --- | --- |
| **SAM3D-GCODE directory** | The folder you cloned the Python engine into. It **must contain `sam3d.py`**. This is the pipeline that does the real work. | The `SAM3D-GCODE` folder from [step 1](#1-clone-both-repositories) — e.g. `~/your-projects/SAM3D-GCODE`. Click **Browse** and select the folder. |
| **DICOM folder** | The folder holding your scan as a series of `.dcm` slice files (one file per slice). | Point it at the **folder** of your CT/MRI series — *not* an individual `.dcm` file. |
| **Output folder** | **An empty folder you create** for the results. The 3D-printable G-code (`output.gcode`) and intermediate files are written here. | Make a **new, empty** folder first (e.g. `mkdir ~/sam3d-output`), then select it. Using a fresh folder keeps results from getting mixed up with other files. |
| **Python binary** | The Python interpreter that has the engine's dependencies installed ([see the list](#what-gets-installed-the-engines-dependencies)) — **not** your system `python3`. The app auto-verifies the binary runs, but it can't check the packages, so make sure it's the one from your `SAM3D_GCODE` environment. | Paste the path you found in [step 3](#3-set-up-the-python-engine) (`which python` / `where python` inside the activated `SAM3D_GCODE` environment). The app auto-verifies it and shows **Ready** when the interpreter responds. |
| **Pipeline quality** | A plain-language stand-in for the engine's slice count (`-s`). **Draft** (8 slices, ~15–20 min) for testing the workflow; **Production** (120 slices, ~3–4 hr) for the final scaffold. | Just click a card. CPU-only? Start with **Draft**. Your choice is remembered for next time. |
| **SAM checkpoint** | The 2.4 GB model file the engine needs for inference. | Click **Download checkpoint** here, or run `python download_checkpoint.py` (see [above](#the-sam-model-checkpoint-24-gb)). |

Once the Python binary shows **Ready** and the checkpoint shows the green ✓, you can advance through
the wizard (annotate slices → run pipeline → done).

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
  "sam3dGcodeDir": "/Users/you/your-projects/SAM3D-GCODE",
  "dicomFolderPath": "/Users/you/scans/patient-001",
  "outputFolderPath": "/Users/you/sam3d-output",
  "pythonPath": "/opt/anaconda3/envs/SAM3D_GCODE/bin/python",
  "slices": 8
}
```

> The folder pickers on the Setup screen fill these in for the current session; the **quality**
> choice and window size auto-save. Setting `sam3dGcodeDir`, `dicomFolderPath`, `outputFolderPath`,
> and `pythonPath` in this file makes them **pre-fill on every launch**.

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
bundles the **desktop app**; end users still need the Python engine + environment as described above.

---

## Troubleshooting

- **"Couldn't run that binary" / Python field shows "Not working".**
  The path isn't the right interpreter. Activate the engine env (`conda activate SAM3D_GCODE`), run
  `which python` (or `where python` on Windows), and paste *that* exact path. The system `python3`
  usually won't have PyTorch/segment-anything installed.
- **The pipeline starts but fails immediately.**
  Almost always a missing engine dependency or checkpoint. Re-run `pip install -r requirements.txt`
  in the activated environment, and confirm `SAM3D-GCODE/checkpoints/sam_vit_h_4b8939.pth` exists.
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
- [`SAM3D_DESKTOP_PLAN.md`](SAM3D_DESKTOP_PLAN.md) — the design document and single source of truth.
- [`CLAUDE.md`](CLAUDE.md) — critical rules (chief among them: **never modify the `SAM3D-GCODE`
  engine** — it's the read-only research pipeline).

---

## License

See [LICENSE](LICENSE).
