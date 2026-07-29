This is a Kotlin Multiplatform project targeting Android, Desktop (JVM).

## Installing Halo

### macOS — Apple Silicon and Intel

```
brew tap Darkwebplayer/halo
brew trust darkwebplayer/halo
brew install --cask halo
```

The `brew trust` line is not optional. Homebrew 6.0 refuses to load a cask from a third-party tap
until you trust it explicitly — a tap can contain arbitrary unsandboxed Ruby, so brew makes you opt
in once per tap. Without it, `install` fails with *"Refusing to load cask … from untrusted tap"*.

**If you install the `.dmg` by hand instead**, macOS will say *"Halo is damaged and can't be
opened"* on first launch. It is not damaged. Halo is ad-hoc signed but not
[notarized](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution),
which requires a paid Apple Developer account, so macOS flags the download. Clear the flag once:

```
xattr -dr com.apple.quarantine /Applications/Halo.app
```

The Homebrew cask runs exactly that command for you in a `postflight` block, which is why the brew
route needs no extra step. The manual command is documented here as the fallback if a future
Homebrew release stops allowing that.

Right-clicking → Open no longer works as a bypass — Apple removed it in macOS 15.

### Windows

```
winget install Infyplus.Halo
```

The MSI is unsigned, so SmartScreen may warn on first run; choose *More info → Run anyway*.

### Android

Download the `.apk` from the [latest release](../../releases/latest) and allow installs from your
browser when prompted. Halo is not on the Play Store.

### Linux

A `.deb` is attached to each release: `sudo dpkg -i Halo-<version>-amd64.deb`.

## Releasing

Releases are cut by pushing a tag; [`.github/workflows/release.yml`](./.github/workflows/release.yml)
does the rest.

```
git tag v1.2.3 && git push origin v1.2.3
```

The version flows into every artifact through the `haloVersion` Gradle property, so a local build
matches CI exactly:

```
./gradlew -PhaloVersion=1.2.3 :desktopApp:packageDistributionForCurrentOS
```

Two things are manual the first time only: submitting the initial version to
`microsoft/winget-pkgs` with `wingetcreate new`, and committing the generated `Casks/halo.rb`
(attached to each release) into `Darkwebplayer/homebrew-halo`.

### A note on macOS signing

`jpackage` leaves its output **entirely unsigned**, and arm64 macOS refuses to execute unsigned
binaries at all — not a Gatekeeper prompt, a hard failure. So the desktop build ad-hoc signs the
`.app` between the app image and the `.dmg` (see the `adHocSignApp` task in
[`desktopApp/build.gradle.kts`](./desktopApp/build.gradle.kts)). This costs nothing and needs no
Apple account. Do not remove it — the Apple Silicon build will not launch without it, and the
failure only shows up on a real Apple Silicon machine.

If you ever buy a Developer ID certificate, set the `MACOS_SIGN`, `MACOS_SIGNING_IDENTITY` and
`NOTARIZATION_*` secrets. The build switches to real signing and notarization, the ad-hoc step
skips itself, and the cask's `postflight` becomes unnecessary.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop tests: `./gradlew :shared:jvmTest`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…