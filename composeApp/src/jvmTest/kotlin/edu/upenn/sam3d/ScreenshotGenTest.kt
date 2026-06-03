package edu.upenn.sam3d

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import edu.upenn.sam3d.domain.model.Axis
import edu.upenn.sam3d.domain.model.DicomSeries
import edu.upenn.sam3d.domain.model.PipelineProgress
import edu.upenn.sam3d.domain.model.PipelineStage
import edu.upenn.sam3d.domain.model.SliceAnnotation
import edu.upenn.sam3d.domain.model.WizardStep
import edu.upenn.sam3d.domain.model.embedVoxel
import edu.upenn.sam3d.state.CheckpointDownload
import edu.upenn.sam3d.state.PipelineError
import edu.upenn.sam3d.state.PythonStatus
import androidx.compose.runtime.Composable
import edu.upenn.sam3d.state.WizardState
import edu.upenn.sam3d.ui.SplashScreen
import edu.upenn.sam3d.ui.theme.AppTheme
import edu.upenn.sam3d.ui.wizard.WizardShellContent
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Dev-only: renders each wizard screen headlessly (Compose [ImageComposeScene] → PNG) so the Carbon
 * redesign can be eyeballed without a display/TCC. Skipped unless run with env `SAM3D_SCREENSHOTS=true`
 * so it never slows the normal suite or runs in CI.
 */
class ScreenshotGenTest {

    private val outDir = File("build/screenshots").apply { mkdirs() }

    @Test
    fun `render all screens to png`() {
        assumeTrue("set SAM3D_SCREENSHOTS=true to generate", System.getenv("SAM3D_SCREENSHOTS") == "true")

        val paths = "/Users/you/SAM3D-GCODE"
        val baseReady = WizardState(
            sam3dGcodeDir = paths,
            dicomFolderPath = "/Users/you/Research/Sample-Data/00000304",
            outputFolderPath = "/Users/you/Research/OUTPUT",
            pythonPath = "/opt/anaconda3/envs/sam3d/bin/python",
            pythonStatus = PythonStatus.VERIFIED,
            checkpointExists = true,
        )

        shotComposable("00-splash") { AppTheme { SplashScreen() } }

        shot("01-start", baseReady)

        shot("02-start-downloading", baseReady.copy(
            checkpointExists = false,
            checkpointDownload = CheckpointDownload.InProgress(receivedBytes = 1_010_000_000, totalBytes = 2_400_000_000),
        ))

        shot("03-prompting", baseReady.copy(
            currentStep = WizardStep.PROMPTING,
            dicomSeries = syntheticSeries(140),
            annotations = listOf(
                SliceAnnotation(
                    axis = Axis.AXIS_2, sliceIndex = 70,
                    positivePolylines = listOf(listOf(
                        embedVoxel(Axis.AXIS_2, 70, 58, 54),
                        embedVoxel(Axis.AXIS_2, 70, 80, 58),
                        embedVoxel(Axis.AXIS_2, 70, 86, 82),
                        embedVoxel(Axis.AXIS_2, 70, 64, 86),
                    )),
                    negativePolylines = listOf(listOf(
                        embedVoxel(Axis.AXIS_2, 70, 40, 40),
                        embedVoxel(Axis.AXIS_2, 70, 48, 36),
                    )),
                ),
            ),
        ), settleMs = 900)

        shot("04-processing", baseReady.copy(
            currentStep = WizardStep.PROCESSING,
            pipelineProgress = PipelineProgress(
                stage = PipelineStage.RUNNING_INFERENCE,
                stagePercentage = 0.45f,
                detail = "Running SAM inference: 3 / 6",
                etaSeconds = 480, // tqdm-derived → "~8 min remaining"
            ),
        ))

        // The earlier bug showed only step 1; this state proves all five stages render at step 1,
        // with the upcoming four greyed.
        shot("04b-processing-step1", baseReady.copy(
            currentStep = WizardStep.PROCESSING,
            pipelineProgress = PipelineProgress(stage = PipelineStage.LOADING_DICOM, detail = "Reading DICOM series…"),
        ))

        shot("05-processing-error", baseReady.copy(
            currentStep = WizardStep.PROCESSING,
            pipelineProgress = PipelineProgress(stage = PipelineStage.RUNNING_INFERENCE, stagePercentage = 0.32f),
            error = PipelineError.Server(
                code = 1,
                body = "Traceback (most recent call last):\n  File \"sam3d.py\", line 156, in <module>\n    masks = predictor.predict(...)\n  File \"torch/cuda/memory.py\", line 33\nRuntimeError: CUDA out of memory. Tried to allocate 2.00 GiB",
                logPath = "/Users/you/Library/Application Support/SAM3D/logs/sam3d-20260603-011045.log",
                hint = "The machine ran out of GPU/CPU memory. Try a smaller volume or close other apps.",
            ),
        ))

        shot("06-done", baseReady.copy(
            currentStep = WizardStep.DONE,
            outputGcodePath = "/Users/you/Research/OUTPUT/output.gcode",
            annotations = List(3) { SliceAnnotation(Axis.AXIS_2, it, listOf(listOf(embedVoxel(Axis.AXIS_2, it, 1, 1))), emptyList()) },
        ))

        println("Screenshots written to ${outDir.absolutePath}")
    }

    private fun shot(name: String, state: WizardState, settleMs: Long = 250) =
        shotComposable(name, settleMs) { AppTheme { WizardShellContent(state = state) {} } }

    private fun shotComposable(name: String, settleMs: Long = 250, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 2560, height = 1600, density = Density(2f), content = content)
        try {
            // Pump a few frames so LaunchedEffects (slice decode, prefill) can settle.
            repeat(6) { scene.render(it * 16_000_000L); Thread.sleep(settleMs / 6) }
            val img = scene.render()
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(outDir, "$name.png").writeBytes(png)
            println("wrote $name.png")
        } finally {
            scene.close()
        }
    }

    private fun syntheticSeries(s: Int): DicomSeries {
        val cube = ByteArray(s * s * s)
        val c = s / 2.0
        for (h in 0 until s) for (w in 0 until s) {
            val dy = h - c; val dx = w - c
            val r = Math.sqrt(dx * dx + dy * dy) / (s / 2.0)
            for (n in 0 until s) {
                val dz = (n - c) / (s / 2.0)
                val body = ((1.0 - r) * 0.85 + (1.0 - Math.abs(dz)) * 0.15).coerceIn(0.0, 1.0)
                val tissue = body * 210 + ((h xor w xor n) % 16) // subtle texture
                cube[h * s * s + w * s + n] = tissue.toInt().coerceIn(0, 255).toByte()
            }
        }
        return DicomSeries("/synthetic", s, Triple(s, s, s), cube)
    }
}
