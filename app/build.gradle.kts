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
        minSdk = 24
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
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
