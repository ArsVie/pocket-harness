// :app — Android shell. Compose UI, no logic (SPEC §1: `:app` renders UiState and nothing else).
//
// AGP 9.x brings built-in Kotlin: `org.jetbrains.kotlin.android` is intentionally absent (it is
// incompatible with AGP 9's new DSL). The Kotlin version used here is the KGP that the root build
// puts on the classpath (2.4.20), the same one :core compiles with.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.arsvie.pocketharness"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arsvie.pocketharness"
        minSdk = 28 // ADR-005 §8: same pin as targetSdk. Also the floor for java.lang.Process#waitFor(timeout)
        targetSdk = 28 // deliberate: keeps exec of app-data files legal (ADR-001 / ADR-005 §8)
        versionCode = 2
        versionName = "0.2.0"

        // The bundled shell (GNU bash 5.3, ADR-006) ships as per-ABI assets — `userland/bash-x86_64`
        // and `userland/bash-aarch64` — picked at runtime from Build.SUPPORTED_ABIS; no jniLibs.

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Required for BuildConfig.DEBUG, which gates the debug-only env bootstrap. AGP 8+ defaults
        // this off, so it must be explicit.
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // Still a no-op: nothing lands in jniLibs — the bundled shell exec's from app data
            // (ADR-006). Kept so a future native fallback can turn legacy packaging on.
            useLegacyPackaging = true
        }
    }

    lint {
        // ExpiredTargetSdkVersion is a Google Play *distribution* policy. Play is explicitly out of
        // scope for this PoC (ADR-001 "Play distribution is out of scope"; ADR-005 §8): targetSdk 28
        // is what keeps exec of app-data files legal on Android 10+ (the W^X behaviour change), and
        // that was verified on the API-36 emulator before this line was written. Every other lint
        // check stays on and the build stays fatal on them.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":core"))

    // Shell.exec is suspend; the probe runs it with runBlocking off the main thread.
    implementation(libs.kotlinx.coroutines.core)

    // okhttp comes transitively from :core, which exposes it as `api` because `OpenAiClient` takes an
    // OkHttpClient in its constructor. Declaring it again here would be duplication.

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
