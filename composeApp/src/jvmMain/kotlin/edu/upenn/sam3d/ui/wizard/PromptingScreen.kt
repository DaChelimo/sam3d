package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.dicom.Dcm4cheLoader
import edu.upenn.sam3d.dicom.DicomBitmapCache
import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.state.DrawingMode
import edu.upenn.sam3d.state.WizardIntent
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.canvas.AnnotationOverlay
import edu.upenn.sam3d.ui.canvas.DicomCanvas
import edu.upenn.sam3d.ui.canvas.displayToVoxelXY
import edu.upenn.sam3d.ui.canvas.letterboxRect
import edu.upenn.sam3d.ui.components.CarbonButton
import edu.upenn.sam3d.ui.components.CarbonButtonSize
import edu.upenn.sam3d.ui.components.CarbonButtonVariant
import edu.upenn.sam3d.ui.components.CarbonIcons
import edu.upenn.sam3d.ui.components.CarbonInlineNotification
import edu.upenn.sam3d.ui.components.CarbonSkeleton
import edu.upenn.sam3d.ui.components.CarbonStatus
import edu.upenn.sam3d.ui.theme.Carbon
import kotlin.math.roundToInt

/**
 * Step 2 — DICOM annotation (§5.3). Carbon toolbar with an **anatomically-labelled** axis switcher
 * (Axial / Coronal / Sagittal computed from the DICOM orientation), a framed slice canvas with a
 * shimmer placeholder, a slice slider, plain-language drawing controls, and a strip of **annotated
 * slices** you can click to jump to and ✕ to delete. Click-to-place / keyboard behaviour is unchanged.
 */
@Composable
fun PromptingScreen(state: WizardState, onIntent: (WizardIntent) -> Unit) {
    val c = Carbon.theme
    val loader = remember { Dcm4cheLoader() }
    val series = state.dicomSeries
    val cubeSize = series?.cubeSize ?: 0
    val maxSlice = (cubeSize - 1).coerceAtLeast(0)
    val planes = series?.axisPlanes ?: listOf("Coronal", "Sagittal", "Axial")

    var axis by remember { mutableStateOf(Axis.AXIS_2) }   // axial by default, like sam3d.py
    var sliceIndex by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(DrawingMode.POSITIVE) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val bitmapCache = remember(series) { DicomBitmapCache() }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.dicomFolderPath, series == null) {
        val folder = state.dicomFolderPath
        if (folder != null && series == null) {
            loadError = null
            runCatching { loader.loadSeries(folder) }
                .onSuccess { onIntent(WizardIntent.DicomSeriesLoaded(it)) }
                .onFailure { loadError = it.message ?: "Failed to load DICOM series" }
        }
    }

    LaunchedEffect(series) {
        if (series != null) {
            sliceIndex = (series.cubeSize / 2).coerceIn(0, series.cubeSize - 1)
            runCatching { focusRequester.requestFocus() }
        }
    }

    LaunchedEffect(series, axis, sliceIndex) {
        val s = series ?: return@LaunchedEffect
        bitmap = bitmapCache.get(axis, sliceIndex)
            ?: loader.loadSliceBitmap(s, axis, sliceIndex)?.also { bitmapCache.put(axis, sliceIndex, it) }
        for (delta in intArrayOf(-1, 1, -2, 2)) {
            val n = sliceIndex + delta
            if (n in 0..maxSlice && bitmapCache.get(axis, n) == null) {
                loader.loadSliceBitmap(s, axis, n)?.let { bitmapCache.put(axis, n, it) }
            }
        }
    }

    // ── Local actions (behaviour unchanged) ────────────────────────────────────
    fun goToSlice(index: Int) {
        val clamped = index.coerceIn(0, maxSlice)
        if (clamped != sliceIndex) {
            sliceIndex = clamped
            onIntent(WizardIntent.EndPolyline)
        }
    }

    fun switchAxis(target: Axis) {
        if (target != axis) {
            axis = target
            sliceIndex = sliceIndex.coerceIn(0, maxSlice)
            onIntent(WizardIntent.EndPolyline)
        }
    }

    // Jump straight to an annotated slice (used by the chip strip): set both axis and index.
    fun navigateTo(targetAxis: Axis, targetSlice: Int) {
        axis = targetAxis
        sliceIndex = targetSlice.coerceIn(0, maxSlice)
        onIntent(WizardIntent.EndPolyline)
        runCatching { focusRequester.requestFocus() }
    }

    fun addPointAt(pos: Offset) {
        if (cubeSize <= 0 || canvasSize == IntSize.Zero) return
        val rect = letterboxRect(canvasSize.width.toFloat(), canvasSize.height.toFloat(), cubeSize)
        if (!rect.contains(pos)) return
        val (vx, vy) = displayToVoxelXY(pos.x, pos.y, rect, cubeSize)
        onIntent(WizardIntent.AddPolylinePoint(axis, sliceIndex, vx, vy, mode))
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || cubeSize <= 0) return false
        when (event.key) {
            Key.A -> mode = if (mode == DrawingMode.POSITIVE) DrawingMode.NEGATIVE else DrawingMode.POSITIVE
            Key.W -> { mode = DrawingMode.POSITIVE; onIntent(WizardIntent.EndPolyline) }
            Key.S -> { mode = DrawingMode.NEGATIVE; onIntent(WizardIntent.EndPolyline) }
            Key.D -> onIntent(WizardIntent.DeleteLastPoint(axis, sliceIndex, mode))
            Key.DirectionLeft -> goToSlice(sliceIndex - 1)
            Key.DirectionRight -> goToSlice(sliceIndex + 1)
            Key.Zero, Key.NumPad0 -> switchAxis(Axis.AXIS_0)
            Key.One, Key.NumPad1 -> switchAxis(Axis.AXIS_1)
            Key.Two, Key.NumPad2 -> switchAxis(Axis.AXIS_2)
            else -> return false
        }
        return true
    }

    val current = state.annotations.firstOrNull { it.axis == axis && it.sliceIndex == sliceIndex }
    val positivePolylines = current?.positivePolylines ?: emptyList()
    val negativePolylines = current?.negativePolylines ?: emptyList()
    // (axis, slice) of every slice that carries an annotation, sorted for the chip strip.
    val annotatedSlices = state.annotations
        .filter { a -> a.positivePolylines.any { it.isNotEmpty() } || a.negativePolylines.any { it.isNotEmpty() } }
        .map { it.axis to it.sliceIndex }
        .sortedWith(compareBy({ it.first.ordinal }, { it.second }))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Carbon.spacing.spacing06)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent(::handleKey)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing05),
    ) {
        // ── Toolbar: axis (anatomical) + slice readout ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing02)) {
                Text("Viewing plane", style = Carbon.type.label01, color = c.textHelper)
                AxisSwitcher(
                    planes = planes,
                    selected = when (axis) { Axis.AXIS_0 -> 0; Axis.AXIS_1 -> 1; Axis.AXIS_2 -> 2 },
                    enabled = series != null,
                    onSelect = { switchAxis(listOf(Axis.AXIS_0, Axis.AXIS_1, Axis.AXIS_2)[it]) },
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing02)) {
                Text("Slice", style = Carbon.type.label01, color = c.textHelper)
                Text(if (series != null) "$sliceIndex / $maxSlice" else "—", style = Carbon.type.heading03, color = c.textPrimary)
            }
        }

        // ── Canvas ──
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .background(Color.Black).border(1.dp, c.borderSubtle01, RectangleShape)
                .onSizeChanged { canvasSize = it },
            contentAlignment = Alignment.Center,
        ) {
            DicomCanvas(
                bitmap = bitmap, cubeSize = cubeSize,
                onPointerDown = { pos -> runCatching { focusRequester.requestFocus() }; addPointAt(pos) },
                onPointerMove = { }, onPointerUp = { },
            )
            AnnotationOverlay(positivePolylines, negativePolylines, axis, sliceIndex, cubeSize)
            when {
                loadError != null -> Box(Modifier.padding(Carbon.spacing.spacing07)) {
                    CarbonInlineNotification(title = "Could not load DICOM", subtitle = loadError, status = CarbonStatus.ERROR)
                }
                series == null -> { CarbonSkeleton(Modifier.fillMaxSize()); Text("Loading DICOM volume…", style = Carbon.type.body01, color = c.textOnColor) }
                bitmap == null -> CarbonSkeleton(Modifier.fillMaxHeight(0.9f).aspectRatio(1f))
            }
        }

        // ── Slice slider ──
        Column {
            Slider(
                value = sliceIndex.toFloat(),
                onValueChange = { goToSlice(it.roundToInt()) },
                valueRange = 0f..maxSlice.toFloat().coerceAtLeast(1f),
                enabled = series != null,
                colors = SliderDefaults.colors(thumbColor = c.interactive, activeTrackColor = c.interactive, inactiveTrackColor = c.layerAccent01),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0", style = Carbon.type.label01, color = c.textHelper)
                Text("$maxSlice", style = Carbon.type.label01, color = c.textHelper)
            }
        }

        // ── Drawing mode + actions ──
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing06)) {
            Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing02)) {
                Text("Marker type", style = Carbon.type.label01, color = c.textHelper)
                ModeSwitcher(mode = mode, enabled = series != null, onSelect = { mode = it })
            }
            Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing02)) {
                Text("Edit", style = Carbon.type.label01, color = c.textHelper)
                Row(horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
                    CarbonButton("New outline", { onIntent(WizardIntent.EndPolyline) }, variant = CarbonButtonVariant.TERTIARY, size = CarbonButtonSize.MD, enabled = series != null)
                    CarbonButton("Undo point", { onIntent(WizardIntent.DeleteLastPoint(axis, sliceIndex, mode)) }, variant = CarbonButtonVariant.GHOST, size = CarbonButtonSize.MD, enabled = series != null, icon = CarbonIcons.ArrowLeft)
                    CarbonButton("Clear slice", { onIntent(WizardIntent.ClearSlice(axis, sliceIndex)) }, variant = CarbonButtonVariant.GHOST, size = CarbonButtonSize.MD, enabled = series != null)
                }
            }
        }
        Text(
            "Click on the scan to drop points and trace a region. " +
                "Green (include) marks the target; red (exclude) carves parts back out.",
            style = Carbon.type.helperText01, color = c.textHelper,
        )

        // ── Annotated slices (jump + delete) ──
        if (annotatedSlices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
                Text("ANNOTATED SLICES · ${annotatedSlices.size}", style = Carbon.type.label01, color = c.textHelper)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03)) {
                    annotatedSlices.forEach { (a, idx) ->
                        val isHere = a == axis && idx == sliceIndex
                        SliceChip(
                            label = "${planeShort(planes, a)} $idx",
                            selected = isHere,
                            onClick = { navigateTo(a, idx) },
                            onDelete = { onIntent(WizardIntent.ClearSlice(a, idx)) },
                        )
                    }
                }
                Text("Click a slice to jump to it · ✕ removes its markers", style = Carbon.type.label01, color = c.textHelper)
            }
        }
    }
}

/** Two-line segmented control: the anatomical plane up top (what a clinician reads), axis number below. */
@Composable
private fun AxisSwitcher(planes: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val c = Carbon.theme
    Row(Modifier.height(Carbon.size.fieldHeightLg).border(1.dp, c.borderStrong01, RectangleShape), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(c.borderSubtle02))
            val isSelected = i == selected
            Column(
                modifier = Modifier.fillMaxHeight()
                    .background(if (isSelected) c.layer03 else Color.Transparent)
                    .then(if (enabled) Modifier.clickable { onSelect(i) }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                    .padding(horizontal = Carbon.spacing.spacing05),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(planes.getOrElse(i) { "—" }, style = Carbon.type.headingCompact01, color = if (!enabled) c.textDisabled else if (isSelected) c.textPrimary else c.textSecondary)
                Text("Axis $i", style = Carbon.type.label01, color = c.textHelper)
            }
        }
    }
}

@Composable
private fun ModeSwitcher(mode: DrawingMode, enabled: Boolean, onSelect: (DrawingMode) -> Unit) {
    val c = Carbon.theme
    val items = listOf(DrawingMode.POSITIVE to ("Include" to c.annotationPositive), DrawingMode.NEGATIVE to ("Exclude" to c.annotationNegative))
    Row(Modifier.height(Carbon.size.fieldHeightMd).border(1.dp, c.borderStrong01, RectangleShape), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, (m, labelColor) ->
            if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(c.borderSubtle02))
            val isSelected = m == mode
            Row(
                modifier = Modifier.fillMaxHeight()
                    .background(if (isSelected) c.layer03 else Color.Transparent)
                    .then(if (enabled) Modifier.clickable { onSelect(m) }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                    .padding(horizontal = Carbon.spacing.spacing05),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing03),
            ) {
                Canvas(Modifier.size(8.dp)) { drawCircle(labelColor.second) }
                Text(labelColor.first, style = Carbon.type.bodyCompact01, color = if (!enabled) c.textDisabled else if (isSelected) c.textPrimary else c.textSecondary)
            }
        }
    }
}

@Composable
private fun SliceChip(label: String, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val c = Carbon.theme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Carbon.size.radiusTag))
            .background(if (selected) c.layer03 else c.layer02)
            .then(if (selected) Modifier.border(1.dp, c.borderInteractive, RoundedCornerShape(Carbon.size.radiusTag)) else Modifier)
            .clickable { onClick() }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = Carbon.spacing.spacing04, end = Carbon.spacing.spacing02, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Carbon.spacing.spacing02),
    ) {
        Text(label, style = Carbon.type.label01, color = c.textPrimary)
        Icon(
            CarbonIcons.Close, contentDescription = "Delete this slice's markers", tint = c.iconSecondary,
            modifier = Modifier.size(16.dp).clip(RoundedCornerShape(50)).clickable { onDelete() }.pointerHoverIcon(PointerIcon.Hand).padding(2.dp),
        )
    }
}

/** Short plane tag for a chip, e.g. "Axial 148". */
private fun planeShort(planes: List<String>, axis: Axis): String =
    planes.getOrElse(when (axis) { Axis.AXIS_0 -> 0; Axis.AXIS_1 -> 1; Axis.AXIS_2 -> 2 }) { "Axis" }
