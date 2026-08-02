import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import javax.inject.Inject
import org.gradle.process.ExecOperations

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

/** Supplied by CI from the git tag; the default keeps a plain `./gradlew packageDmg` working. */
val haloVersion = (findProperty("haloVersion") as String?) ?: "1.0.0"

// Read through `providers` rather than System.getenv so the configuration cache tracks them.
// All absent — the normal case, and the current intended one — means an unsigned build.
val macSign = providers.environmentVariable("MACOS_SIGN").orNull == "true"

compose.desktop {
    application {
        mainClass = "dev.infyplus.halo.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // Names the installer and the installed application, so this is what the user sees:
            // "Halo-1.2.3.dmg" and "Halo.app". It was the bundle id, which produced a
            // "dev.infyplus.halo-1.0.0.dmg" that reads like a build artefact rather than an app.
            packageName = "Halo"
            packageVersion = haloVersion
            description = "A floating assistant that captures what you say and reminds you later."
            vendor = "Infyplus"

            // jpackage takes one format per platform and silently ignores the wrong one, so the
            // same artwork ships three times: icons/ holds the exports, not the source.
            windows { iconFile.set(project.file("icons/halo.ico")) }
            linux { iconFile.set(project.file("icons/halo.png")) }

            macOS {
                iconFile.set(project.file("icons/halo.icns"))

                // Kept as the reverse-DNS identifier now that packageName is the display name.
                // Also what `zap trash:` in the Homebrew cask targets.
                bundleID = "dev.infyplus.halo"

                /**
                 * Off unless MACOS_SIGN=true, which is deliberate.
                 *
                 * Without a Developer ID certificate, jpackage still applies an *ad-hoc* signature,
                 * and that is enough for the app to execute on Apple Silicon — the only thing left
                 * blocking it is the quarantine flag, which the Homebrew cask's postflight clears.
                 * Turning this on later is supplying the secrets, not changing this file.
                 */
                signing {
                    sign.set(macSign)
                    identity.set(providers.environmentVariable("MACOS_SIGNING_IDENTITY"))
                }
                notarization {
                    appleID.set(providers.environmentVariable("NOTARIZATION_APPLE_ID"))
                    // An app-specific password from appleid.apple.com, not the account password.
                    password.set(providers.environmentVariable("NOTARIZATION_PASSWORD"))
                    teamID.set(providers.environmentVariable("NOTARIZATION_TEAM_ID"))
                }
            }
        }
    }
}
// The render tool in src/test needs a JUnit runner; everything else it uses is already here.
dependencies {
    testImplementation(libs.kotlin.testJunit)
}

tasks.withType<Test> { useJUnit() }

/**
 * Ad-hoc sign the .app before it is sealed into a .dmg.
 *
 * **This is what makes the Apple Silicon build runnable at all**, and it is not optional. arm64
 * macOS refuses to execute a mach-O with no signature whatsoever — not a Gatekeeper prompt, a hard
 * kernel refusal — and jpackage leaves its output entirely unsigned when given no identity
 * (verified: `codesign -dvv` on a fresh app image reports "code object is not signed at all").
 * An ad-hoc signature satisfies that requirement, costs nothing, and needs no Apple account.
 *
 * It is deliberately NOT a `doLast` on the packaging task: `packageDmg` regenerates the app image,
 * so anything signed afterwards is discarded, and anything signed by a separate task afterwards is
 * signing a copy nobody ships. Wiring it to `createDistributable` — which the package tasks depend
 * on — is what places it between "app image exists" and "app image gets zipped into a dmg".
 *
 * Skipped when MACOS_SIGN is set, because a real Developer ID signature comes from Compose's own
 * `signing {}` block and re-signing over it would strip it back to ad-hoc.
 */
abstract class AdHocSignApp : DefaultTask() {

    /**
     * Deliberately `@Internal`, not `@InputDirectory`.
     *
     * This task is wired with `finalizedBy`, so it still runs when the task that produces the app
     * image has failed — and an `@InputDirectory` pointing at a directory that was never created
     * fails *validation*, burying the real error under a second, more confusing one. Being an
     * internal property also means Gradle never considers this up to date, which is correct: the
     * task modifies the very directory it reads.
     */
    @get:Internal
    abstract val appDir: DirectoryProperty

    /**
     * Carried as a task input rather than an `onlyIf { }` block. The lambda would close over the
     * build script, and the configuration cache cannot serialize a script object reference.
     */
    @get:Input
    abstract val skip: Property<Boolean>

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun sign() {
        if (skip.get()) return
        val app = appDir.get().asFile
        // Absent means the app image never got built, so the real failure is already being
        // reported by the task that should have produced it. Say nothing and get out of the way.
        if (!app.exists()) return
        // --deep is discouraged for real distribution signing, where each nested binary should be
        // signed bottom-up with its own identity. For an ad-hoc pass over a bundled JRE containing
        // hundreds of dylibs it is the whole point, and there are no entitlements to preserve.
        exec.exec { commandLine("codesign", "--force", "--deep", "--sign", "-", app.absolutePath) }
        logger.lifecycle("ad-hoc signed ${app.name} — required for it to run on Apple Silicon")
    }
}

if (providers.systemProperty("os.name").get().startsWith("Mac")) {
    val adHocSign = tasks.register<AdHocSignApp>("adHocSignApp") {
        appDir.set(layout.buildDirectory.dir("compose/binaries/main/app/Halo.app"))
        // A real Developer ID signature comes from Compose's own signing {} block; re-signing over
        // it would strip it back to ad-hoc, which is strictly worse.
        skip.set(macSign)
    }
    // matching/configureEach rather than named(): the Compose plugin registers these lazily, so a
    // strict lookup at configuration time fails outright.
    tasks.matching { it.name == "createDistributable" }.configureEach { finalizedBy(adHocSign) }
}
