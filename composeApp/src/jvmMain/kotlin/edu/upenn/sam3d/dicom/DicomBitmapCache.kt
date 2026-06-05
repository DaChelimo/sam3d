package edu.upenn.sam3d.dicom

import androidx.compose.ui.graphics.ImageBitmap
import edu.upenn.sam3d.AppConfig
import edu.upenn.sam3d.domain.model.Axis

class DicomBitmapCache(private val maxSize: Int = AppConfig.maxCachedBitmaps) {

    private val cache = object : LinkedHashMap<Pair<Axis, Int>, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Pair<Axis, Int>, ImageBitmap>): Boolean =
            size > maxSize
    }

    @Synchronized
    fun get(axis: Axis, index: Int): ImageBitmap? = cache[Pair(axis, index)]

    @Synchronized
    fun put(axis: Axis, index: Int, bitmap: ImageBitmap) {
        cache[Pair(axis, index)] = bitmap
    }

    @Synchronized
    fun clear() = cache.clear()

    @Synchronized
    fun size(): Int = cache.size
}
