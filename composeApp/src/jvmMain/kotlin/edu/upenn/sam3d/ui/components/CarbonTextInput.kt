package edu.upenn.sam3d.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.ui.theme.Carbon
import edu.upenn.sam3d.ui.theme.CarbonSize

/**
 * Carbon text input (https://carbondesignsystem.com/components/text-input/usage): an optional label
 * above, a filled field with a single strong bottom border, optional helper or error text below.
 * Focus draws the 2px `$focus` ring; an error swaps it for the 2px `$support-error` border plus an
 * inline error glyph. Built on [BasicTextField] so the chrome is exactly Carbon's, not Material's.
 */
@Composable
fun CarbonTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    height: Dp = CarbonSize.fieldHeightLg,
) {
    val c = Carbon.theme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val isError = errorText != null

    Column(modifier = modifier) {
        if (label != null) {
            Text(label, style = Carbon.type.label01, color = c.textSecondary)
            Spacer(Modifier.height(Carbon.spacing.spacing03))
        }
        val fieldBg = when {
            !enabled -> c.field01
            hovered && !focused -> c.fieldHover01
            else -> c.field01
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(fieldBg, RectangleShape)
                .then(
                    when {
                        !enabled -> Modifier.bottomBorder(1.dp, c.borderSubtle01)
                        focused -> Modifier.border(CarbonSize.focusWidth, c.focus, RectangleShape)
                        isError -> Modifier.border(CarbonSize.focusWidth, c.supportError, RectangleShape)
                        else -> Modifier.bottomBorder(1.dp, c.borderStrong01)
                    }
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f).padding(horizontal = Carbon.spacing.spacing05)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = singleLine,
                        textStyle = Carbon.type.body01.copy(color = if (enabled) c.textPrimary else c.textDisabled),
                        cursorBrush = SolidColor(c.focus),
                        interactionSource = interaction,
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (value.isEmpty() && placeholder != null) {
                                Text(
                                    placeholder, style = Carbon.type.body01, color = c.textPlaceholder,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        },
                    )
                }
                if (isError) {
                    CarbonStatusGlyph(
                        status = CarbonStatus.ERROR, color = c.supportError, knockout = c.background,
                        modifier = Modifier.padding(end = Carbon.spacing.spacing05).size(Carbon.size.iconMd),
                    )
                }
            }
        }
        val sub = errorText ?: helperText
        if (sub != null) {
            Spacer(Modifier.height(Carbon.spacing.spacing02))
            Text(
                sub,
                style = Carbon.type.helperText01,
                color = if (isError) c.textError else c.textHelper,
            )
        }
    }
}

/** Draws only a bottom border — Carbon's resting text-field treatment. */
private fun Modifier.bottomBorder(width: Dp, color: androidx.compose.ui.graphics.Color): Modifier =
    drawBehind {
        val h = width.toPx()
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
            size = androidx.compose.ui.geometry.Size(size.width, h),
        )
    }
