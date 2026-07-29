import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/**
 * The version being built, supplied by CI from the git tag and defaulting to something sane so a
 * plain `./gradlew assembleRelease` still works on a laptop.
 */
val haloVersion = (findProperty("haloVersion") as String?) ?: "1.0.0"

/**
 * A monotonic integer for `versionCode`, derived from the version rather than from a CI counter.
 *
 * Android refuses to install an APK whose code is not greater than the installed one, and it is the
 * *only* thing it compares — the name is decoration. Deriving it arithmetically keeps a locally
 * built 1.2.3 identical to CI's 1.2.3, which a `github.run_number` could never manage.
 *
 * Each field gets two digits, so this holds until a minor or patch reaches 100.
 */
val haloVersionCode = haloVersion.substringBefore('-').split('.').let { parts ->
    fun part(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: 0
    part(0) * 10_000 + part(1) * 100 + part(2)
}

/**
 * Release signing, supplied by the environment.
 *
 * Read through `providers` rather than `System.getenv` so Gradle's configuration cache — enabled in
 * `gradle.properties` — can track them properly. All four absent (a normal laptop) leaves the
 * release build unsigned rather than failing, so `assembleRelease` stays runnable locally.
 */
val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val keystoreAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val keystoreKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasSigningConfig = !keystorePath.isNullOrBlank() &&
    !keystorePassword.isNullOrBlank() &&
    !keystoreAlias.isNullOrBlank() &&
    !keystoreKeyPassword.isNullOrBlank()

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    // The overlay and permission gate are Android-only Compose UI, so this module needs the
    // Compose artifacts directly — :shared exposes them as implementation, not api.
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "dev.infyplus.halo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.infyplus.halo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = haloVersionCode
        versionName = haloVersion
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }
    buildTypes {
        release {
            // Left unsigned when no keystore was supplied. An unsigned APK cannot be installed, but
            // it can be *built*, which is what keeps `assembleRelease` useful for checking that the
            // release variant compiles without handing every developer the signing key.
            if (hasSigningConfig) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}