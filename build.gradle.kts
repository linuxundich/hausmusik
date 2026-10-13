// AGP 9 ships Kotlin itself (android.builtInKotlin). The classpath entry raises
// the bundled Kotlin to the version in the catalogue; :sonos applies the plain
// JVM plugin from the same classpath.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
