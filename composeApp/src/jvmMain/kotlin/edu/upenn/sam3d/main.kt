package edu.upenn.sam3d

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SAM3D",
    ) {
        App()
    }
}