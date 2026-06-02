package edu.upenn.sam3d.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AnnotationFile(
    val positive: List<List<List<Int>>>,
    val negative: List<List<List<Int>>>
)
