import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    // :shared exposes Compose as implementation, not api, so this module needs them directly.
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // Global hotkey. The JVM cannot observe keystrokes while another app has focus, so this
    // installs a native OS-level hook. On macOS it requires Accessibility permission.
    implementation(libs.jnativehook)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "dev.infyplus.halo.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "dev.infyplus.halo"
            packageVersion = "1.0.0"
        }
    }
}
// The render tool in src/test needs a JUnit runner; everything else it uses is already here.
dependencies {
    testImplementation(libs.kotlin.testJunit)
}

tasks.withType<Test> { useJUnit() }
