package edu.upenn.sam3d.domain.model

data class DicomSeries(
    val folderPath: String,
    val cubeSize: Int,
    val rawShape: Triple<Int, Int, Int>,  // H × W × N before padding
    val cube: ByteArray = ByteArray(0),   // S³, row-major: cube[h*S*S + w*S + n]
    // Anatomical plane shown when slicing along each axis, indexed [AXIS_0, AXIS_1, AXIS_2].
    // Computed from ImageOrientationPatient; falls back to the conventional axial-acquisition mapping.
    val axisPlanes: List<String> = listOf("Coronal", "Sagittal", "Axial"),
) {
    // ByteArray equality by reference is intentional; we never compare series structurally
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DicomSeries) return false
        return folderPath == other.folderPath && cubeSize == other.cubeSize &&
               rawShape == other.rawShape && cube === other.cube
    }
    override fun hashCode(): Int = folderPath.hashCode() * 31 + cubeSize
}
