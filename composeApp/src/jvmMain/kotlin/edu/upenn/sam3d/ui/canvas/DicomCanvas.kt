package edu.upenn.sam3d.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Renders one S×S padded-cube slice [bitmap] letterboxed inside the canvas (§8.2) and reports
 * pointer activity in **display-space** [Offset]s. The caller converts those to voxels with
 * [displayToVoxelXY] / [displayToVoxel] using the same [letterboxRect].
 *
 * Pointer handling uses the [awaitPointerEventScope] loop from §5.3 — NOT detectDragGestures /
 * detectTapGestures, which conflict when combined. Callbacks are read through [rememberUpdatedState]
 * so the long-lived `pointerInput(Unit)` handler always invokes the latest lambdas even as the
 * active axis / slice / drawing mode change.
 */
@Composable
fun DicomCanvas(
    bitmap: ImageBitmap?,
    cubeSize: Int,
    modifier: Modifier = Modifier,
    onPointerDown: (Offset) -> Unit,
    onPointerMove: (Offset) -> Unit,
    onPointerUp: () -> Unit,
) {
    val currentDown by rememberUpdatedState(onPointerDown)
    val currentMove by rememberUpdatedState(onPointerMove)
    val currentUp by rememberUpdatedState(onPointerUp)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when {
                            change.pressed && !change.previousPressed -> currentDown(change.position)
                            change.pressed -> currentMove(change.position)
                            !change.pressed && change.previousPressed -> currentUp()
                        }
                        change.consume()
                    }
                }
            },
    ) {
        val bmp = bitmap ?: return@Canvas
        if (cubeSize <= 0) return@Canvas
        val rect = letterboxRect(size.width, size.height, cubeSize)
        drawImage(
            image = bmp,
            dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
            dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
            // Nearest-neighbour upscaling, matching reprompting3d.py's Image.NEAREST.
            filterQuality = FilterQuality.None,
        )
    }
}
