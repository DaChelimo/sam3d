package edu.upenn.sam3d.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.ui.theme.Carbon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Launch splash: the SAM3D mark as a slowly rotating wireframe cube over the dark canvas, with the
 * wordmark beneath. Shown briefly on startup (see App) so the brand is the first thing the user sees,
 * then crossfades into the wizard.
 */
@Composable
fun SplashScreen() {
    val c = Carbon.theme
    val angle by rememberInfiniteTransition(label = "splash").animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            RotatingCube(angle = angle, modifier = Modifier.size(132.dp))
            Spacer(Modifier.height(Carbon.spacing.spacing07))
            Text("SAM3D", style = Carbon.type.heading05, color = c.textPrimary)
            Spacer(Modifier.height(Carbon.spacing.spacing02))
            Text("DICOM → G-code", style = Carbon.type.body01, color = c.textHelper)
        }
    }
}

@Composable
private fun RotatingCube(angle: Float, modifier: Modifier = Modifier) {
    val c = Carbon.theme
    val verts = arrayOf(
        floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f), floatArrayOf(1f, 1f, -1f), floatArrayOf(-1f, 1f, -1f),
        floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f),
    )
    val edges = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7,
    )
    val pitch = 0.62f // fixed iso tilt

    Canvas(modifier) {
        val scale = size.minDimension * 0.30f
        val cx = size.width / 2f; val cy = size.height / 2f
        val a = angle + 0.7f // base yaw so the cube reads as 3D even at the first frame
        fun project(v: FloatArray): Offset {
            // yaw around Y, then pitch around X, orthographic drop of z.
            val x1 = v[0] * cos(a) + v[2] * sin(a)
            val z1 = -v[0] * sin(a) + v[2] * cos(a)
            val y2 = v[1] * cos(pitch) - z1 * sin(pitch)
            return Offset(cx + x1 * scale, cy + y2 * scale)
        }
        val p = verts.map(::project)
        edges.forEach { (a, b) ->
            drawLine(c.interactive, p[a], p[b], strokeWidth = 3f, cap = StrokeCap.Round)
        }
        p.forEach { drawCircle(c.textPrimary, radius = 3.2f, center = it) }
    }
}
