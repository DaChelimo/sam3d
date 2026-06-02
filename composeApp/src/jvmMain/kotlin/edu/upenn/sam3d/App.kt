package edu.upenn.sam3d

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import edu.upenn.sam3d.domain.usecase.SaveAnnotationsUseCase
import edu.upenn.sam3d.process.PythonPipelineRunner
import edu.upenn.sam3d.state.WizardViewModel
import edu.upenn.sam3d.ui.theme.AppTheme
import edu.upenn.sam3d.ui.wizard.WizardShell

@Composable
fun App() {
    // SaveAnnotationsUseCase writes tempdir/points.json; PythonPipelineRunner spawns sam3d.py and
    // registers the live process with main.kt's shutdown hook (activeProcessManager).
    val viewModel = remember {
        val runner = PythonPipelineRunner(
            logDir = OsUtils.userDataDir().resolve("logs"),
            onManagerStarted = { activeProcessManager = it },
        )
        WizardViewModel(annotationSaver = SaveAnnotationsUseCase(), pipelineRunner = runner)
    }
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            WizardShell(viewModel)
        }
    }
}
