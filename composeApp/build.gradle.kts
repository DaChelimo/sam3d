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

compose.desktop {
    application {
        mainClass = "edu.upenn.sam3d.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SAM3D"
            packageVersion = "1.1.0"
            description = "DICOM → G-code: SAM3D scaffold tool-path generator"
            vendor = "University of Pennsylvania"

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
