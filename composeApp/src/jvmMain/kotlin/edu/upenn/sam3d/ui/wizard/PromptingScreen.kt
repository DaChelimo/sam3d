package edu.upenn.sam3d.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.roundToInt

private val PositiveGreen = Color(0xFF4CAF50)
private val NegativeRed = Color(0xFFE53935)

/**
 * Step 2 — DICOM annotation (§5.3). The user draws green (positive) / red (negative) polylines on
 * the padded-cube slices; clicks are converted to voxel coordinates and accumulated in the
 * WizardViewModel. "Run Pipeline" (in the shell's bottom bar) dispatches RunPipeline, which writes
 * tempdir/points.json (§9) and advances to processing.
 *
 * Drawing is click-to-place (matching reprompting3d.py): each click adds a vertex to the current
 * polyline; "New Line" / W / S start a fresh one; changing slice or axis also starts a fresh one.
 */
@Composable
fun PromptingScreen(state: WizardState, onIntent: (WizardIntent) -> Unit) {
    val loader = remember { Dcm4cheLoader() }
    val series = state.dicomSeries
    val cubeSize = series?.cubeSize ?: 0
    val maxSlice = (cubeSize - 1).coerceAtLeast(0)

    var axis by remember { mutableStateOf(Axis.AXIS_2) }   // axial by default, like sam3d.py
    var sliceIndex by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(DrawingMode.POSITIVE) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val bitmapCache = remember(series) { DicomBitmapCache() }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val focusRequester = remember { FocusRequester() }

    // Load the padded cube once per DICOM folder, then cache it in WizardState.
    LaunchedEffect(state.dicomFolderPath, series == null) {
        val folder = state.dicomFolderPath
        if (folder != null && series == null) {
            loadError = null
            runCatching { loader.loadSeries(folder) }
                .onSuccess { onIntent(WizardIntent.DicomSeriesLoaded(it)) }
                .onFailure { loadError = it.message ?: "Failed to load DICOM series" }
        }
    }

    // Centre the slice when a series first appears, and keep focus for keyboard shortcuts.
    LaunchedEffect(series) {
        if (series != null) {
            sliceIndex = (series.cubeSize / 2).coerceIn(0, series.cubeSize - 1)
            runCatching { focusRequester.requestFocus() }
        }
    }

    // Decode the visible slice (and prefetch neighbours) whenever axis/slice/series change.
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

    // ── Local actions ──────────────────────────────────────────────────────────
    fun goToSlice(index: Int) {
        val clamped = index.coerceIn(0, maxSlice)
        if (clamped != sliceIndex) {
            sliceIndex = clamped
            onIntent(WizardIntent.EndPolyline) // a new slice starts a new polyline
        }
    }

    fun switchAxis(target: Axis) {
        if (target != axis) {
            axis = target
            sliceIndex = sliceIndex.coerceIn(0, maxSlice)
            onIntent(WizardIntent.EndPolyline)
        }
    }

    fun addPointAt(pos: Offset) {
        if (cubeSize <= 0 || canvasSize == IntSize.Zero) return
        val rect = letterboxRect(canvasSize.width.toFloat(), canvasSize.height.toFloat(), cubeSize)
        if (!rect.contains(pos)) return // ignore clicks in the letterbox bars
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
    val annotatedSlices = state.annotations.count { a ->
        a.positivePolylines.any { it.isNotEmpty() } || a.negativePolylines.any { it.isNotEmpty() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent(::handleKey)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Axis switcher + slice label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AxisSwitcher(axis = axis, enabled = series != null, onSelect = ::switchAxis)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (series != null) "Slice: $sliceIndex / $maxSlice" else "Slice: —",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Canvas (bitmap + annotation overlay)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .onSizeChanged { canvasSize = it },
            contentAlignment = Alignment.Center,
        ) {
            DicomCanvas(
                bitmap = bitmap,
                cubeSize = cubeSize,
                onPointerDown = { pos -> runCatching { focusRequester.requestFocus() }; addPointAt(pos) },
                onPointerMove = { /* click-to-place: nothing on drag */ },
                onPointerUp = { /* polyline continues until New Line / slice change */ },
            )
            AnnotationOverlay(
                positivePolylines = positivePolylines,
                negativePolylines = negativePolylines,
                axis = axis,
                sliceIndex = sliceIndex,
                cubeSize = cubeSize,
            )
            when {
                loadError != null -> Text(
                    "Could not load DICOM: $loadError",
                    color = MaterialTheme.colorScheme.error,
                )
                series == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Loading DICOM volume…", color = Color.White)
                }
                bitmap == null -> CircularProgressIndicator()
            }
        }

        // Slice slider
        Slider(
            value = sliceIndex.toFloat(),
            onValueChange = { goToSlice(it.roundToInt()) },
            valueRange = 0f..maxSlice.toFloat().coerceAtLeast(1f),
            enabled = series != null,
        )

        // Drawing-mode toggle
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Drawing mode:", style = MaterialTheme.typography.labelLarge)
            RadioButton(selected = mode == DrawingMode.POSITIVE, onClick = { mode = DrawingMode.POSITIVE })
            Text("Positive (Green)", color = PositiveGreen)
            Spacer(Modifier.width(8.dp))
            RadioButton(selected = mode == DrawingMode.NEGATIVE, onClick = { mode = DrawingMode.NEGATIVE })
            Text("Negative (Red)", color = NegativeRed)
        }

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onIntent(WizardIntent.EndPolyline) }, enabled = series != null) {
                Text("New Line")
            }
            OutlinedButton(
                onClick = { onIntent(WizardIntent.DeleteLastPoint(axis, sliceIndex, mode)) },
                enabled = series != null,
            ) { Text("Delete Last Point") }
            OutlinedButton(
                onClick = { onIntent(WizardIntent.ClearSlice(axis, sliceIndex)) },
                enabled = series != null,
            ) { Text("Clear This Slice") }
        }

        Text(
            "Keyboard: A toggle ± · W new positive · S new negative · D delete point · ←→ slice ±1 · 0/1/2 axis",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Annotations: $annotatedSlices slice(s) annotated",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AxisSwitcher(axis: Axis, enabled: Boolean, onSelect: (Axis) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Axis.AXIS_0 to "Axis 0", Axis.AXIS_1 to "Axis 1", Axis.AXIS_2 to "Axis 2")
            .forEach { (value, label) ->
                if (value == axis) {
                    Button(onClick = { onSelect(value) }, enabled = enabled) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }, enabled = enabled) { Text(label) }
                }
            }
    }
}
