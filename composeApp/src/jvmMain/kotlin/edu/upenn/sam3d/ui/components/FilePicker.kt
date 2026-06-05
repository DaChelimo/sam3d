package edu.upenn.sam3d.ui.components

import edu.upenn.sam3d.OsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

enum class FilePickerMode { FOLDER, FILE }

/**
 * Opens a **native** file/folder chooser.
 *  - macOS: AWT [FileDialog] is the real NSOpenPanel (the Finder-style sheet). Folder selection uses
 *    the `apple.awt.fileDialogForDirectories` switch so directories are pickable in that same panel.
 *  - Windows/Linux: [JFileChooser] under the system look-and-feel, so it matches the OS theme rather
 *    than the dated cross-platform Metal look.
 *
 * Runs on the EDT (via invokeAndWait) off the IO dispatcher, so the calling coroutine just suspends.
 */
suspend fun showFilePicker(
    title: String,
    mode: FilePickerMode = FilePickerMode.FOLDER,
    initialDirectory: File? = null,
): String? = withContext(Dispatchers.IO) {
    if (OsUtils.isMac()) nativeMacDialog(title, mode, initialDirectory)
    else systemChooser(title, mode, initialDirectory)
}

private fun nativeMacDialog(title: String, mode: FilePickerMode, initialDirectory: File?): String? {
    val forDirectories = mode == FilePickerMode.FOLDER
    val previous = System.getProperty("apple.awt.fileDialogForDirectories")
    if (forDirectories) System.setProperty("apple.awt.fileDialogForDirectories", "true")
    try {
        var result: String? = null
        onEdt {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
                initialDirectory?.let { directory = it.absolutePath }
            }
            dialog.isVisible = true // modal — blocks until the user picks or cancels
            val dir = dialog.directory
            val file = dialog.file
            result = if (dir != null && file != null) File(dir, file).absolutePath else null
        }
        return result
    } finally {
        if (forDirectories) {
            if (previous == null) System.clearProperty("apple.awt.fileDialogForDirectories")
            else System.setProperty("apple.awt.fileDialogForDirectories", previous)
        }
    }
}

private fun systemChooser(title: String, mode: FilePickerMode, initialDirectory: File?): String? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    var result: String? = null
    onEdt {
        val chooser = JFileChooser(initialDirectory).apply {
            dialogTitle = title
            fileSelectionMode = when (mode) {
                FilePickerMode.FOLDER -> JFileChooser.DIRECTORIES_ONLY
                FilePickerMode.FILE -> JFileChooser.FILES_ONLY
            }
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            result = chooser.selectedFile?.absolutePath
        }
    }
    return result
}

private fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
}
