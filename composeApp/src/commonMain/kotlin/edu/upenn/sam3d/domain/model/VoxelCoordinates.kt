package edu.upenn.sam3d.domain.model

/**
 * Embeds an in-plane voxel coordinate (voxelX, voxelY) together with the active-axis slice index
 * into a full [x, y, z] padded-cube voxel point, following the coordinate convention in §8.3:
 *
 *   AXIS_0 → [slice, x, y]    AXIS_1 → [x, slice, y]    AXIS_2 → [x, y, slice]
 *
 * This is the single source of truth for that convention. Both [displayToVoxel] (jvmMain canvas)
 * and [edu.upenn.sam3d.state.WizardViewModel] (commonMain) call it, so the slice-index position can
 * never drift between the click-capture path and the storage path — the [x,y,z]-order risk in §14.
 */
fun embedVoxel(axis: Axis, sliceIndex: Int, voxelX: Int, voxelY: Int): IntArray = when (axis) {
    Axis.AXIS_0 -> intArrayOf(sliceIndex, voxelX, voxelY)
    Axis.AXIS_1 -> intArrayOf(voxelX, sliceIndex, voxelY)
    Axis.AXIS_2 -> intArrayOf(voxelX, voxelY, sliceIndex)
}
