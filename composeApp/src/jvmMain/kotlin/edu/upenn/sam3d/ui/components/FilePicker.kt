package edu.upenn.sam3d.ui.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

enum class FilePickerMode { FOLDER, FILE }

suspend fun showFilePicker(
    title: String,
    mode: FilePickerMode = FilePickerMode.FOLDER,
    initialDirectory: File? = null,
): String? = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater {
            val chooser = JFileChooser(initialDirectory).apply {
                dialogTitle = title
                fileSelectionMode = when (mode) {
                    FilePickerMode.FOLDER -> JFileChooser.DIRECTORIES_ONLY
                    FilePickerMode.FILE -> JFileChooser.FILES_ONLY
                }
            }
            val result = chooser.showOpenDialog(null)
            if (cont.isActive) {
                cont.resume(
                    if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath
                    else null
                )
            }
        }
    }
}
