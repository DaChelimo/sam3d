# Handoff Plan #1 — Verify the AXIS_2 annotation → segmentation geometry

> **For the next Claude instance.** Self-contained. Read this top-to-bottom, then the referenced
> files, before touching code. The project memory (`MEMORY.md` → `project_coordinate_convention.md`,
> `project_sam3d.md`) is loaded automatically and complements this.

## Objective
Determine **definitively** whether a region drawn on an **AXIS_2** slice in the desktop app produces
a SAM segmentation in the **same** location (vs a diagonally-reflected / transposed one). If it's
wrong, fix it and update the spec + tests.

This is the **one open correctness question**. Phases 1–5 are complete and the pipeline runs
end-to-end (a real 495 MB `output.gcode` was produced), so this is *not* "does it run" — it's "are
the AXIS_2 coordinates geometrically faithful to what the user drew."

## Why this matters (background)
The annotation coordinate convention is split across two places that must agree:
- **Desktop:** `displayToVoxel` (§8.3, `composeApp/.../ui/canvas/CoordinateTransforms.kt`) +
  `embedVoxel` (`composeApp/.../domain/model/VoxelCoordinates.kt`) decide the stored `[x,y,z]`.
- **Engine ground truth:** `reprompting3d.py` `add_point()` (the Tk tool that generated
  `points.fixture.json`) is what the pipeline was designed around.

`reprompting3d.py:add_point` does `y, x = event.x, event.y` (a swap) then, for axis 2, stores
`(int(x/scale), int(y/scale), slice)` = `(vertical, horizontal, slice)`. §8.3 stores
`[voxelX, voxelY, slice]` = `(horizontal, vertical, slice)`. **For AXIS_2 these are transposed.**
`scale_transform.parse_prompts` uses the coordinates positionally in the cube, so a transpose
reflects the prompt across the diagonal → the wrong region (unless the drawn shape is symmetric).

The pipeline still produced a large, non-degenerate point cloud / G-code, so the bug (if real) does
**not** crash — it silently misplaces AXIS_2 prompts. Hence: must verify empirically.

## Hypothesis (static analysis — VERIFY, do not trust blindly)
Tracing `Dcm4cheLoader.loadSliceBitmap` (the on-screen orientation) together with §8.3 + the Python
tool:

| Axis | Desktop stored `[a,b,slice-pos]` | reprompting3d stored | Match? |
|------|----------------------------------|----------------------|--------|
| AXIS_0 | `[slice, w, n]` | `[slice, w, n]` | ✅ coincides |
| AXIS_1 | `[h, slice, n]` | `[h, slice, n]` | ✅ coincides |
| AXIS_2 | `[w, h, slice]` | `[h, w, slice]` | ❌ **x/y swapped** |

The asymmetry is real: it comes from the per-axis bitmap orientations chosen in Phase 2's
`loadSliceBitmap` interacting with §8.3's "voxelX first" rule. **This table is a hypothesis** — the
analysis is intricate and easy to get wrong. The experiment below is the source of truth.

## How to verify (the authoritative experiment)
1. **Draw a deliberately ASYMMETRIC mark on an AXIS_2 slice.** A symmetric blob cannot reveal a
   transpose. E.g. a small polygon clearly in the **top-left** quadrant of an axial slice that
   intersects the bone. (Use the app, or hand-write a `tempdir/points.json`.)
2. **Run the pipeline** (see cheat-sheet). It writes the segmentation mask as a DICOM series to
   `<outputDir>/temp/segmentation_mask/`.
3. **Overlay & compare:** render the *input* DICOM slice you drew on, your polyline, and the
   corresponding `segmentation_mask` slice. Confirm the mask sits where you drew (top-left), not in
   the mirrored quadrant. A small Python script with `pydicom` + `matplotlib` (run via the `sam3d`
   env) is the fastest way to render all three.
4. **Cross-check (optional, strongest):** annotate the *same* shape with `reprompting3d.py` (the Tk
   tool) and diff its `points.json` against the desktop's for AXIS_2 — a coordinate swap confirms it.

## The fix, IF a transpose is confirmed
The convention lives in **one** function by design:
`composeApp/src/commonMain/kotlin/edu/upenn/sam3d/domain/model/VoxelCoordinates.kt :: embedVoxel`.
- Change `AXIS_2 -> intArrayOf(voxelX, voxelY, sliceIndex)` to match the engine
  (likely `intArrayOf(voxelY, voxelX, sliceIndex)`); re-derive AXIS_1/AXIS_0 from the experiment too.
- **Ripple effects (all required):**
  - `CoordinateTransformsTest` and the §8.4 worked example (`pointer (250,180) → [128,153,45]`) will
    change — update both. The fixture test (`SaveAnnotationsUseCaseTest`) is unaffected (it embeds
    literal coords), but re-confirm.
  - Update `SAM3D_DESKTOP_PLAN.md` §8.3/§8.4 so the spec matches reality.
  - Update memory `project_coordinate_convention.md` to record the resolution.
- **Decision note:** the engine (`reprompting3d.py` + the fixture) is the source of truth for
  correctness — if the experiment contradicts §8.3, §8.3 is wrong and must change. Do **not**
  "preserve" §8.3 just because it's the written spec.

## Acceptance criteria
- An asymmetric AXIS_2 annotation yields a segmentation in the matching location (verified visually).
- If a fix was made: `./gradlew :composeApp:jvmTest` green; §8.3/§8.4 + the transform test updated;
  memory updated. Re-run the end-to-end once more to confirm a sane mask.

## Key references
- `SAM3D_DESKTOP_PLAN.md` §8 (all — coordinate frames + worked example), §9 (JSON).
- `composeApp/src/commonMain/.../domain/model/VoxelCoordinates.kt` (`embedVoxel` — the convention).
- `composeApp/src/jvmMain/.../ui/canvas/CoordinateTransforms.kt` (`displayToVoxel`/`voxelToDisplay`).
- `composeApp/src/jvmMain/.../dicom/Dcm4cheLoader.kt :: loadSliceBitmap` (per-axis bitmap orientation — the other half of the mapping).
- `../SAM3D-GCODE/reprompting3d.py` `add_point()` (~line 250) and `draw_polyline()` (~185).
- `../SAM3D-GCODE/scale_transform.py` `parse_prompts()` (line 124) — how the coords are consumed.
- Memory: `project_coordinate_convention.md` (the full analysis + prior evidence).

## Operational cheat-sheet (critical — see memory `project_sam3d.md`)
- **Python env (ALWAYS):** `/opt/anaconda3/envs/sam3d/bin/python`. Base/`python3` lacks pydicom.
- **Run reusing an existing `tempdir/points.json`** (detached, survives the 10-min tool cap):
  ```
  cd /Users/DaChelimo/Documents/Research/SAM3D-GCODE
  nohup bash -c 'printf "done\n" | /opt/anaconda3/envs/sam3d/bin/python -u sam3d.py \
    -p /Users/DaChelimo/Documents/Research/Sample-Data/00000304 \
    --reprompt 0 --reslice 0 -s 8 -o /Users/DaChelimo/Documents/Research/OUTPUT --datatype dcm' \
    > /tmp/sam3d_verify.log 2>&1 & disown
  ```
  - `printf 'done\n'` clears sam3d.py's interactive point-cloud `input()` loop (else EOFError).
  - **Never pass `-v`** — `args.version` becomes the string "1" ≠ int 1 → `predictor` UnboundLocalError.
  - `-s 8` ≈ 15–21 min (reslice dominates). DICOM is `Sample-Data/00000304` (S=562).
- The mask DICOM series lands in `<outputDir>/temp/segmentation_mask/`; the gcode at `<outputDir>/output.gcode`.
- Never modify anything under `../SAM3D-GCODE/` (engine is read-only) — work around in Kotlin.
