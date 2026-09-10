// PocketHarness — root build.
//
// Versions live only in gradle/libs.versions.toml (SPEC §1: single version catalogue).
// AGP 9.4.0 has built-in Kotlin (KGP is a runtime dependency of AGP), so :app does not apply
// org.jetbrains.kotlin.android. Declaring KGP here pins the version used by *all* modules, which
// has to match :core's Kotlin/JVM plugin so the two modules agree on metadata.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
