package edu.upenn.sam3d.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.domain.model.embedVoxel

/**
 * Coordinate-frame conversions between the three frames in §8: display pixels → display rect →
 * cube voxels. Implemented exactly per §8.2 (letterbox), §8.3 (displayToVoxel) and §8.5
 * (voxelToDisplay). Getting these wrong produces silent inference failures, so they are unit-tested
 * against the §8.4 worked example.
 */

/**
 * §8.2 — the centred, aspect-ratio-preserving rectangle the S×S slice bitmap occupies inside a
 * [canvasWidth] × [canvasHeight] canvas. Equal black bars appear on two sides when the canvas is
 * not square (the slice always is, being S×S).
 */
fun letterboxRect(canvasWidth: Float, canvasHeight: Float, cubeSize: Int): Rect {
    val scale = minOf(canvasWidth / cubeSize, canvasHeight / cubeSize)
    val displayW = cubeSize * scale
    val displayH = cubeSize * scale
    val offsetX = (canvasWidth - displayW) / 2f
    val offsetY = (canvasHeight - displayH) / 2f
    return Rect(offsetX, offsetY, offsetX + displayW, offsetY + displayH)
}

/**
 * §8.3 (scaling half) — display-pixel pointer → in-plane (voxelX, voxelY), clamped to the cube.
 * The active-axis slice index is added separately by [embedVoxel]. Exposed on its own so the
 * Prompting screen can dispatch the two in-plane coordinates to the ViewModel.
 */
fun displayToVoxelXY(
    pointerX: Float,
    pointerY: Float,
    displayRect: Rect,
    cubeSize: Int,
): Pair<Int, Int> {
    val voxelX = ((pointerX - displayRect.left) / displayRect.width * cubeSize)
        .toInt().coerceIn(0, cubeSize - 1)
    val voxelY = ((pointerY - displayRect.top) / displayRect.height * cubeSize)
        .toInt().coerceIn(0, cubeSize - 1)
    return voxelX to voxelY
}

/**
 * §8.3 (full) — display-pixel pointer → [x, y, z] padded-cube voxel point with the slice index
 * embedded at the active-axis position. Composes [displayToVoxelXY] with [embedVoxel] so the
 * scaling and the convention each live in exactly one place.
 */
fun displayToVoxel(
    pointerX: Float,
    pointerY: Float,
    displayRect: Rect,
    cubeSize: Int,
    axis: Axis,
    sliceIndex: Int,
): IntArray {
    val (voxelX, voxelY) = displayToVoxelXY(pointerX, pointerY, displayRect, cubeSize)
    return embedVoxel(axis, sliceIndex, voxelX, voxelY)
}

/**
 * §8.5 — render a stored voxel [point] back to a display-pixel [Offset]. Returns null when the
 * point does not lie on the slice currently shown for [axis] (so the overlay only draws the points
 * belonging to the visible slice).
 */
fun voxelToDisplay(
    point: IntArray,
    axis: Axis,
    sliceIndex: Int,
    displayRect: Rect,
    cubeSize: Int,
): Offset? {
    val (vx, vy) = when (axis) {
        Axis.AXIS_0 -> if (point[0] != sliceIndex) return null else point[1] to point[2]
        Axis.AXIS_1 -> if (point[1] != sliceIndex) return null else point[0] to point[2]
        Axis.AXIS_2 -> if (point[2] != sliceIndex) return null else point[0] to point[1]
    }
    return Offset(
        displayRect.left + vx.toFloat() / cubeSize * displayRect.width,
        displayRect.top + vy.toFloat() / cubeSize * displayRect.height,
    )
}
