package edu.upenn.sam3d.domain.repository

import androidx.compose.ui.graphics.ImageBitmap
import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.domain.model.DicomSeries

interface DicomRepository {
    suspend fun loadSeries(folderPath: String): DicomSeries
    suspend fun loadSliceBitmap(series: DicomSeries, axis: Axis, index: Int): ImageBitmap?
}
