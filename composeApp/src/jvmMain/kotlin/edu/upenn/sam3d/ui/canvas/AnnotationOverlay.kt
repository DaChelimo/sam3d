package edu.upenn.sam3d.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import edu.upenn.sam3d.domain.model.Axis

// Green = positive, red = negative — the colour convention from reprompting3d.py / §5.3.
private val PositiveGreen = Color(0xFF4CAF50)
private val NegativeRed = Color(0xFFE53935)

/**
 * Draws the positive (green) and negative (red) polylines for the currently shown slice on top of
 * the [DicomCanvas]. Each voxel point is projected to display space with [voxelToDisplay]; points
 * that do not belong to the shown slice come back null and are skipped, so the overlay aligns with
 * the bitmap drawn underneath it (both use the same [letterboxRect] from the shared canvas size).
 *
 * This composable is purely visual — it installs no pointer input, so clicks fall through to the
 * [DicomCanvas] layered beneath it.
 */
@Composable
fun AnnotationOverlay(
    positivePolylines: List<List<IntArray>>,
    negativePolylines: List<List<IntArray>>,
    axis: Axis,
    sliceIndex: Int,
    cubeSize: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (cubeSize <= 0) return@Canvas
        val rect = letterboxRect(size.width, size.height, cubeSize)
        drawPolylines(positivePolylines, PositiveGreen, axis, sliceIndex, rect, cubeSize)
        drawPolylines(negativePolylines, NegativeRed, axis, sliceIndex, rect, cubeSize)
    }
}

private fun DrawScope.drawPolylines(
    polylines: List<List<IntArray>>,
    color: Color,
    axis: Axis,
    sliceIndex: Int,
    rect: androidx.compose.ui.geometry.Rect,
    cubeSize: Int,
) {
    for (polyline in polylines) {
        val points = polyline.mapNotNull { voxelToDisplay(it, axis, sliceIndex, rect, cubeSize) }
        for (i in points.indices) {
            if (i > 0) {
                drawLine(
                    color = color,
                    start = points[i - 1],
                    end = points[i],
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(color = color, radius = 4f, center = points[i])
        }
    }
}
