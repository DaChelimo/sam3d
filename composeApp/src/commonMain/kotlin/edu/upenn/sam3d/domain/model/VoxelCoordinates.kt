package edu.upenn.sam3d.domain.model

/**
 * Embeds an in-plane click (voxelX = screen-x, voxelY = screen-y) together with the active-axis
 * slice index into a padded-cube voxel point `[d0, d1, d2]`, where `[d0, d1, d2]` is the cube ARRAY
 * INDEX of the voxel under the cursor. The engine's `scale_transform.parse_prompts` consumes the
 * point positionally (`cube[d0][d1][d2]`) with no axis-specific handling, so the stored order must
 * be the array index of exactly the clicked voxel. The per-axis order is dictated by
 * `Dcm4cheLoader.loadSliceBitmap`'s on-screen orientation for each axis:
 *
 *   AXIS_0 (fixes H=dim0): screen x→w (dim1), y→n (dim2) → [slice, voxelX, voxelY]
 *   AXIS_1 (fixes W=dim1): screen x→h (dim0), y→n (dim2) → [voxelX, slice, voxelY]
 *   AXIS_2 (fixes N=dim2): screen x→w (dim1), y→h (dim0) → [voxelY, voxelX, slice]  ← voxelX/voxelY swap
 *
 * AXIS_2 swaps voxelX/voxelY because its bitmap shows screen-x = w (dim1) and screen-y = h (dim0).
 * Storing `[voxelX, voxelY, slice]` there would transpose the prompt across the cube diagonal and the
 * engine would segment the mirrored location. This matches the engine's own `reprompting3d.py`
 * `add_point` (which stores `(voxelY, voxelX, slice)` for axis 2) and was confirmed end-to-end —
 * see §8.3/§8.4 and docs/axis2_verification/.
 *
 * This is the single source of truth for that convention. Both [displayToVoxel] (jvmMain canvas)
 * and [edu.upenn.sam3d.state.WizardViewModel] (commonMain) call it, so the slice-index position can
 * never drift between the click-capture path and the storage path — the [x,y,z]-order risk in §14.
 */
fun embedVoxel(axis: Axis, sliceIndex: Int, voxelX: Int, voxelY: Int): IntArray = when (axis) {
    Axis.AXIS_0 -> intArrayOf(sliceIndex, voxelX, voxelY)
    Axis.AXIS_1 -> intArrayOf(voxelX, sliceIndex, voxelY)
    Axis.AXIS_2 -> intArrayOf(voxelY, voxelX, sliceIndex)
}
