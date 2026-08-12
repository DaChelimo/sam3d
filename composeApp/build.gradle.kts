import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.turbine)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.dcm4che.core)
            implementation(libs.dcm4che.imageio)
            implementation(libs.slf4j.api)
            implementation(libs.slf4j.simple)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.mockk)
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}


tasks.withType<Test> {
    jvmArgs("-Xmx3g")
}

/**
 * Stage the vendored Python engine into the app's resources so **installed builds ship it**.
 *
 * Without this, `packageMsi`/`packageDmg`/`createDistributable` produce an app image containing only
 * the executable, the bundled JRE and the app jars — no engine. The app then failed to find
 * `pipeline/` and told users to "run the app from the project root", which is impossible advice for
 * anyone who installed from a `.msi` or the portable zip: they hit a disabled Setup button and a
 * `requirements.txt` that doesn't exist on their machine. Shipping the engine is what makes
 * "download and run" true.
 *
 * Only the sources are staged — ~150 KB of `.py` plus `requirements.txt`. `checkpoints/` (2.4 GB),
 * `tempdir/` and `__pycache__/` are runtime state, are gitignored, and must never enter the image.
 * At runtime [EngineStager] copies this out to a writable per-user directory; see that class for why
 * the app can't just run the engine in place inside Program Files.
 */
// Held as providers so the task body captures no Project/script references (configuration cache).
val appResourcesDir = layout.buildDirectory.dir("appResources")
val engineStagingDir = appResourcesDir.map { it.dir("common/engine") }

val stageEngineResources by tasks.registering(Copy::class) {
    description = "Stages the vendored Python engine into the packaged app's resources."
    from(rootProject.layout.projectDirectory.dir("pipeline")) {
        include("*.py")
        include("requirements.txt")
        include("LICENSE")
    }
    // appResourcesRootDir's direct children are platform buckets; `common` applies to every target.
    into(engineStagingDir)

    // A Copy task over a missing source directory succeeds silently. That would ship an engine-less
    // installer again — the exact failure this task exists to prevent — so assert the result instead.
    val stagedProvider = engineStagingDir
    doLast {
        val staged = stagedProvider.get().asFile
        check(File(staged, "sam3d.py").isFile && File(staged, "requirements.txt").isFile) {
            "Engine staging produced no usable engine in $staged — is pipeline/ present in the checkout? " +
                "Packaging must not continue: the installed app would have nothing to run."
        }
        println("Staged engine for packaging: ${staged.listFiles()?.size ?: 0} files")
    }
}

// The Compose packaging tasks read appResourcesRootDir, so the staging must already have run. Wiring
// it by name covers createDistributable, runDistributable and every packageXxx variant without
// depending on the plugin's internal task types.
tasks.matching { it.name.startsWith("package") || it.name.startsWith("create") || it.name.startsWith("prepareAppResources") }
    .configureEach { dependsOn(stageEngineResources) }

compose.desktop {
    application {
        mainClass = "edu.upenn.sam3d.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SAM3D"
            // Must stay ahead of the newest published tag: Windows MSI upgrade logic keys off this,
            // and an installer numbered at or below the installed build can refuse to replace it.
            packageVersion = "1.2.3"
            description = "DICOM → G-code: SAM3D scaffold tool-path generator"
            vendor = "University of Pennsylvania"

            // Ships the Python engine inside every distribution — see stageEngineResources above.
            appResourcesRootDir.set(appResourcesDir)

            macOS {
                bundleID = "edu.upenn.sam3d"
                iconFile.set(project.file("icons/AppIcon.icns"))
                // Codesign + notarization are wired here at release time — see docs/PACKAGING.md.
            }
            windows {
                iconFile.set(project.file("icons/AppIcon.ico"))
                menuGroup = "SAM3D"
            }
            linux {
                iconFile.set(project.file("icons/AppIcon.png"))
            }
        }
    }
}
