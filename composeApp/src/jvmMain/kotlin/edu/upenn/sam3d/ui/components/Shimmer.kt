package edu.upenn.sam3d.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import edu.upenn.sam3d.ui.theme.Carbon

/**
 * Carbon-style skeleton placeholder (https://carbondesignsystem.com/components/skeleton-states): a
 * quiet `$skeleton-background` block with a lighter highlight band sweeping across it. Replaces
 * spinners during content load (DICOM slice decode, cube load) for a calmer, more polished wait
 * state. Fill it to the area you're standing in for — it takes its size from [modifier].
 */
@Composable
fun CarbonSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    val c = Carbon.theme
    val sweep by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    Canvas(modifier = modifier.clip(shape)) {
        drawRect(c.skeletonBackground)
        val band = size.width * 0.45f
        val start = sweep * (size.width + band) - band
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(c.skeletonBackground, c.skeletonElement, c.skeletonBackground),
                startX = start,
                endX = start + band,
            ),
        )
    }
}
