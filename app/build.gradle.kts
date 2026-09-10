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
        versionCode = 1
        versionName = "0.1.0"

        // The shipped userland is a static aarch64 busybox; the API-36 x86_64 emulator runs it
        // through NDK translation (ENVIRONMENT.md).
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // Only meaningful once something lands in jniLibs/arm64-v8a (the fallback exec path in
            // ADR-001). Today the userland ships as an asset and is unpacked to files/userland, so
            // this is currently a no-op — it becomes load-bearing if W0.3's probe proves the asset
            // path is blocked and the binary has to be packaged as a native library instead.
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

    // :core declares okhttp as `implementation`, so the client type is not on :app's compile
    // classpath transitively; the composition root constructs an OkHttpClient per route.
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
