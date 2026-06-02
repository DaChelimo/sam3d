package edu.upenn.sam3d.domain.model

data class SliceAnnotation(
    val axis: Axis,
    val sliceIndex: Int,
    val positivePolylines: List<List<IntArray>>,
    val negativePolylines: List<List<IntArray>>
)
