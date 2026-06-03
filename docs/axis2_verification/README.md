# AXIS_2 annotation → segmentation geometry — verification & fix

**Question (Handoff Plan #1):** does a region drawn on an **AXIS_2** slice in the desktop app
produce a SAM segmentation in the *same* place, or a diagonally-transposed one?

**Answer: it was transposed. Confirmed empirically and fixed.**

---

## The bug

`scale_transform.parse_prompts` (the engine's prompt consumer) treats a stored point `[d0,d1,d2]`
**positionally** as the padded-cube array index `cube[d0][d1][d2]` — there is no per-axis handling.
So the desktop must store the **array index of the voxel under the cursor**.

`Dcm4cheLoader.loadSliceBitmap` shows the AXIS_2 slice with **screen-x = w (dim1)** and
**screen-y = h (dim0)**. A click therefore lands on cube voxel `(h=voxelY, w=voxelX, slice)`, whose
array index is `[voxelY, voxelX, slice]`. The old `embedVoxel` stored `[voxelX, voxelY, slice]` — the
**transpose** across the cube diagonal. The engine then segmented the mirrored location.

| Axis | loadSliceBitmap orientation | old embedVoxel | correct (array index) | was it wrong? |
|------|------------------------------|----------------|-----------------------|---------------|
| AXIS_0 | x→w, y→n | `[slice, voxelX, voxelY]` | `[slice, voxelX, voxelY]` | no |
| AXIS_1 | x→h, y→n | `[voxelX, slice, voxelY]` | `[voxelX, slice, voxelY]` | no |
| AXIS_2 | x→w, y→h | `[voxelX, voxelY, slice]` | `[voxelY, voxelX, slice]` | **YES** |

AXIS_0/AXIS_1 were already correct: the desktop and the Tk tool display those planes in *transposed*
orientations, so each tool's own formula still nets out to the same array index. AXIS_2 is the only
plane both tools show identically, so the old "voxelX first" rule became a real net transpose.

## The fix (one-line convention change + its inverse)

- `composeApp/src/commonMain/.../domain/model/VoxelCoordinates.kt` — `embedVoxel` AXIS_2 →
  `intArrayOf(voxelY, voxelX, sliceIndex)`.
- `composeApp/src/jvmMain/.../ui/canvas/CoordinateTransforms.kt` — `voxelToDisplay` AXIS_2 →
  `point[1] to point[0]` (kept the inverse, so on-screen overlay dots still sit under the cursor).
- Spec `SAM3D_DESKTOP_PLAN.md` §8.3/§8.4/§8.5 and the unit tests updated; memory updated.

## Evidence (four independent confirmations)

1. **Engine's real `parse_prompts`** (`proof.py`): fed a points.json for an asymmetric mark on the
   fibula at AXIS_2 n=300; the OLD convention places the prompt mirrored into soft tissue, FIXED
   places it on the fibula. See `proof_overlay_n300.png`.
2. **Engine's own Tk tool** `reprompting3d.add_point`: stores `(voxelY, voxelX, slice)` for axis 2 —
   identical to FIXED, different from OLD (table printed by `proof.py`).
3. **Committed fixture** `points.fixture.json` (a real `reprompting3d.py` run): its AXIS_2 points are
   `[H, W, slice]` (dim0 first) — the FIXED order.
4. **Full SAM pipeline end-to-end** (`reconstruct_mask_overlay.py`): ran `sam3d.py` on the FIXED
   points.json; the segmentation mask lands on the fibula we drew. See `sam_mask_overlay_n300.png`.

All three axes re-derived in `proof_all_axes.py` (`proof_axes01_planes.png`): only AXIS_2 changed.

## Gotcha found while verifying (pre-existing engine bug — do NOT fix, engine is read-only)

The engine **saves the mask scrambled**: `utils.load3dmatrix` builds the cube as `(H,W,N)` but the
save path's `load_dicom_series` stacks as `(N,H,W)`, and `remove_symmetrical_cube_padding` crops
against that `(N,H,W)` shape. Net result: mask DICOM `slice_k.dcm` is a **fixed-H plane**, not axial
slice k. To overlay a mask on an AXIS_2 input slice you must reconstruct the cube N-plane from the
stacked mask DICOMs (`mask_cube[h,w,n] = slice_h.dcm[w-25, n-25]`), which `reconstruct_mask_overlay.py`
does. This is orthogonal to the annotation transpose.

## Reproduce

```bash
PY=/opt/anaconda3/envs/sam3d/bin/python
$PY docs/axis2_verification/explore_axis2.py      # loads the real cube (caches /tmp/axis2/cube_f16.npy)
$PY docs/axis2_verification/proof.py              # parse_prompts proof + overlay + points_{FIXED,OLD}.json
$PY docs/axis2_verification/proof_all_axes.py     # all-three-axes faithfulness table

# end-to-end (≈20–30 min): install FIXED prompt, run pipeline, reconstruct overlay
cp docs/axis2_verification/points_FIXED.json ../SAM3D-GCODE/tempdir/points.json
( cd ../SAM3D-GCODE && printf 'done\n' | $PY -u sam3d.py -p /…/Sample-Data/00000304 \
    --reprompt 0 --reslice 0 -s 8 -o /…/OUTPUT_axis2 --datatype dcm )
$PY docs/axis2_verification/reconstruct_mask_overlay.py
```

## Files

- `proof.py`, `proof_all_axes.py`, `reconstruct_mask_overlay.py`, `explore_axis2.py` — scripts
- `points_FIXED.json` / `points_OLD.json` — the asymmetric AXIS_2 mark under each convention
- `proof_overlay_n300.png` — OLD (transposed) vs FIXED (on the fibula) prompt placement
- `sam_mask_overlay_n300.png` — the SAM mask on the n=300 plane (end-to-end)
- `proof_axes01_planes.png`, `contact_sheet_axis2.png`, `zoom_n300.png` — supporting renders
