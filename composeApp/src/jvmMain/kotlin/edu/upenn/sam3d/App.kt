package edu.upenn.sam3d

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.upenn.sam3d.state.WizardViewModel
import edu.upenn.sam3d.ui.SplashScreen
import edu.upenn.sam3d.ui.theme.AppTheme
import edu.upenn.sam3d.ui.theme.Carbon
import edu.upenn.sam3d.ui.wizard.WizardShell
import kotlinx.coroutines.delay

// On macOS the title bar is transparent (set in main.kt) so the window chrome matches the dark app;
// this inset keeps the header clear of the traffic-light buttons.
private val MAC_TITLEBAR_INSET = 28.dp

/**
 * Root UI. The [viewModel] is created and owned by `main.kt` (in the `application {}` scope) so the
 * window's `onCloseRequest` can observe busy state (active checkpoint download / running pipeline)
 * and warn before closing — see main.kt.
 */
@Composable
fun App(viewModel: WizardViewModel) {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(2200); showSplash = false }

    AppTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Carbon.theme.background) {
            Box(Modifier.fillMaxSize().padding(top = if (OsUtils.isMac()) MAC_TITLEBAR_INSET else 0.dp)) {
                Crossfade(targetState = showSplash, animationSpec = tween(600), label = "splash") { splash ->
                    if (splash) SplashScreen() else WizardShell(viewModel)
                }
            }
        }
    }
}
