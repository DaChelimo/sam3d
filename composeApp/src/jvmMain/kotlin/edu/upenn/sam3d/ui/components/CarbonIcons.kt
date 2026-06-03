package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A small, dependency-free icon set drawn in the spirit of the Carbon icon library — geometric,
 * 2px stroke on a 32-unit grid, rounded caps/joins. They are authored as single-colour [ImageVector]s
 * so `Icon(tint = …)` recolours them per context (Carbon `$icon-primary`, status colours, etc.).
 *
 * Two-tone *status* glyphs (the filled circles in notifications) can't be a single tint, so those are
 * the Canvas-drawn [CarbonStatusGlyph] below instead.
 */
object CarbonIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 32.dp, defaultHeight = 32.dp,
            viewportWidth = 32f, viewportHeight = 32f,
        ).apply(block).build()

    private fun ImageVector.Builder.stroke(
        width: Float = 2f,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) = path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

    val ArrowRight: ImageVector by lazy {
        icon("ArrowRight") {
            stroke { moveTo(5f, 16f); lineTo(27f, 16f) }
            stroke { moveTo(18f, 7f); lineTo(27f, 16f); lineTo(18f, 25f) }
        }
    }

    val ArrowLeft: ImageVector by lazy {
        icon("ArrowLeft") {
            stroke { moveTo(27f, 16f); lineTo(5f, 16f) }
            stroke { moveTo(14f, 7f); lineTo(5f, 16f); lineTo(14f, 25f) }
        }
    }

    val Download: ImageVector by lazy {
        icon("Download") {
            stroke { moveTo(16f, 4f); lineTo(16f, 21f) }
            stroke { moveTo(9f, 14f); lineTo(16f, 21f); lineTo(23f, 14f) }
            stroke { moveTo(6f, 27f); lineTo(26f, 27f) }
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            stroke {
                moveTo(4f, 7f); lineTo(12f, 7f); lineTo(15f, 11f); lineTo(28f, 11f)
                lineTo(28f, 26f); lineTo(4f, 26f); close()
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            stroke { moveTo(8f, 8f); lineTo(24f, 24f) }
            stroke { moveTo(24f, 8f); lineTo(8f, 24f) }
        }
    }

    val Checkmark: ImageVector by lazy {
        icon("Checkmark") {
            stroke(width = 2.5f) { moveTo(6f, 17f); lineTo(13f, 24f); lineTo(26f, 8f) }
        }
    }

    val Renew: ImageVector by lazy {
        icon("Renew") {
            // ~300° arc + arrowhead → "start over"
            stroke {
                moveTo(26f, 13f)
                arcToRelative(11f, 11f, 0f, true, false, 2.4f, 6.2f)
            }
            stroke { moveTo(26f, 5f); lineTo(26f, 13f); lineTo(18f, 13f) }
        }
    }

    val Document: ImageVector by lazy {
        icon("Document") {
            stroke {
                moveTo(8f, 4f); lineTo(20f, 4f); lineTo(26f, 10f); lineTo(26f, 28f); lineTo(8f, 28f); close()
            }
            stroke { moveTo(20f, 4f); lineTo(20f, 10f); lineTo(26f, 10f) }
            stroke(width = 1.6f) { moveTo(12f, 16f); lineTo(22f, 16f) }
            stroke(width = 1.6f) { moveTo(12f, 21f); lineTo(22f, 21f) }
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            stroke { moveTo(12f, 8f); lineTo(20f, 16f); lineTo(12f, 24f) }
        }
    }

    val Restart: ImageVector by lazy {
        icon("Restart") {
            stroke { moveTo(16f, 4f); lineTo(16f, 15f) }
            stroke {
                moveTo(10f, 8f)
                arcToRelative(11f, 11f, 0f, true, false, 12f, 0f)
            }
        }
    }
}

/** The two-tone status circle used in notifications & status rows (filled disc + knocked-out glyph). */
enum class CarbonStatus { SUCCESS, ERROR, WARNING, INFO }

/**
 * Draws a Carbon-style filled status glyph: a solid [color] disc with the symbol (✓ / × / ! / i)
 * punched through in [knockout] (the surface colour behind it). Warning uses a dark glyph on the
 * yellow disc, matching Carbon (yellow needs dark contrast).
 */
@Composable
fun CarbonStatusGlyph(
    status: CarbonStatus,
    color: Color,
    knockout: Color,
    modifier: Modifier = Modifier,
) {
    val glyphColor = if (status == CarbonStatus.WARNING) Color(0xFF161616) else knockout
    Canvas(modifier = modifier) {
        val d = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = color, radius = d / 2f, center = c)
        val w = d * 0.09f
        when (status) {
            CarbonStatus.SUCCESS -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - d * 0.20f, c.y + d * 0.02f)
                    lineTo(c.x - d * 0.05f, c.y + d * 0.17f)
                    lineTo(c.x + d * 0.22f, c.y - d * 0.16f)
                }
                drawPath(p, glyphColor, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            CarbonStatus.ERROR -> {
                val r = d * 0.17f
                drawLine(glyphColor, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), w, StrokeCap.Round)
                drawLine(glyphColor, Offset(c.x + r, c.y - r), Offset(c.x - r, c.y + r), w, StrokeCap.Round)
            }
            CarbonStatus.WARNING -> {
                drawLine(glyphColor, Offset(c.x, c.y - d * 0.20f), Offset(c.x, c.y + d * 0.06f), w, StrokeCap.Round)
                drawCircle(glyphColor, radius = w * 0.6f, center = Offset(c.x, c.y + d * 0.17f))
            }
            CarbonStatus.INFO -> {
                drawCircle(glyphColor, radius = w * 0.6f, center = Offset(c.x, c.y - d * 0.18f))
                drawLine(glyphColor, Offset(c.x, c.y - d * 0.04f), Offset(c.x, c.y + d * 0.20f), w, StrokeCap.Round)
            }
        }
    }
}
