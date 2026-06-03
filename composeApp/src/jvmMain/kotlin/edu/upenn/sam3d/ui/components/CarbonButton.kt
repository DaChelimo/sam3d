package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.ui.theme.Carbon
import edu.upenn.sam3d.ui.theme.CarbonColors
import edu.upenn.sam3d.ui.theme.CarbonSize

enum class CarbonButtonVariant { PRIMARY, SECONDARY, TERTIARY, GHOST, DANGER }

enum class CarbonButtonSize(val height: Dp) { SM(32.dp), MD(40.dp), LG(48.dp) }

private data class ButtonPaint(
    val container: Color,
    val containerHover: Color,
    val containerActive: Color,
    val content: Color,
    val contentHover: Color = content,
    val border: Color? = null,
)

/**
 * A Carbon button (https://carbondesignsystem.com/components/button/usage). Square corners, a label
 * in `body-compact-01`, an optional trailing icon Carbon-style at the leading edge of the right
 * padding, and the full Carbon state ramp: rest → hover → active, plus a 2px inset focus ring and a
 * disabled treatment per variant.
 */
@Composable
fun CarbonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: CarbonButtonVariant = CarbonButtonVariant.PRIMARY,
    size: CarbonButtonSize = CarbonButtonSize.LG,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    fillMaxWidth: Boolean = false,
) {
    val c = Carbon.theme
    val paint = paintFor(variant, c)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()

    val container = when {
        !enabled -> if (variant == CarbonButtonVariant.GHOST || variant == CarbonButtonVariant.TERTIARY) Color.Transparent else c.buttonDisabled
        pressed -> paint.containerActive
        hovered -> paint.containerHover
        else -> paint.container
    }
    val content = when {
        !enabled -> c.textDisabled
        hovered && variant == CarbonButtonVariant.TERTIARY -> paint.contentHover
        else -> paint.content
    }
    val border = when {
        !enabled && variant == CarbonButtonVariant.TERTIARY -> c.borderSubtle01
        variant == CarbonButtonVariant.TERTIARY && !hovered -> paint.border
        else -> null
    }

    val widthMod = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier
    Box(
        modifier = modifier
            .then(widthMod)
            .height(size.height)
            .background(container, RectangleShape)
            .then(if (border != null) Modifier.border(1.dp, border, RectangleShape) else Modifier)
            .then(if (focused && enabled) Modifier.focusRing(c) else Modifier)
            .hoverable(interaction, enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minWidth = 0.dp)
                .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(start = 16.dp, end = if (icon != null) 16.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(text, style = Carbon.type.bodyCompact01, color = content, maxLines = 1)
            if (icon != null) {
                if (fillMaxWidth) Spacer(Modifier.weight(1f)) else Spacer(Modifier.width(32.dp))
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(Carbon.size.iconSm))
            }
        }
    }
}

private fun paintFor(variant: CarbonButtonVariant, c: CarbonColors): ButtonPaint = when (variant) {
    CarbonButtonVariant.PRIMARY -> ButtonPaint(
        container = c.buttonPrimary, containerHover = c.buttonPrimaryHover,
        containerActive = c.buttonPrimaryActive, content = c.textOnColor,
    )
    CarbonButtonVariant.SECONDARY -> ButtonPaint(
        container = c.buttonSecondary, containerHover = c.buttonSecondaryHover,
        containerActive = c.buttonSecondaryActive, content = c.textOnColor,
    )
    CarbonButtonVariant.DANGER -> ButtonPaint(
        container = c.buttonDanger, containerHover = c.buttonDangerHover,
        containerActive = c.buttonDangerActive, content = c.textOnColor,
    )
    CarbonButtonVariant.TERTIARY -> ButtonPaint(
        container = Color.Transparent, containerHover = c.textPrimary, containerActive = c.borderStrong01,
        content = c.textPrimary, contentHover = c.background, border = c.textPrimary,
    )
    CarbonButtonVariant.GHOST -> ButtonPaint(
        container = Color.Transparent, containerHover = c.layerHover01, containerActive = c.layerActive01,
        content = c.textPrimary,
    )
}

/** Carbon focus: a 2px ring in `$focus` (white on dark). Drawn as a border so it never reflows. */
private fun Modifier.focusRing(c: CarbonColors): Modifier =
    this.border(CarbonSize.focusWidth, c.focus, RectangleShape)
