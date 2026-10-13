/**
 * Signing data comes only from Gradle properties outside the repository
 * (~/.gradle/gradle.properties). Without them the release build is unsigned
 * instead of failing.
 */
val signingProperties = listOf(
    "HAUSMUSIK_STORE_FILE",
    "HAUSMUSIK_STORE_PASSWORD",
    "HAUSMUSIK_KEY_ALIAS",
    "HAUSMUSIK_KEY_PASSWORD",
).associateWith { providers.gradleProperty(it).orNull }

val canSignRelease = signingProperties.values.none { it.isNullOrBlank() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/** Version name and build number from Git, as in Commentator. */
fun git(vararg args: String): String? = runCatching {
    val execution = providers.exec {
        commandLine(listOf("git") + args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    if (execution.result.get().exitValue != 0) return@runCatching null
    execution.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

val baseVersion: String = providers.gradleProperty("HAUSMUSIK_VERSION").orNull ?: "0.1.0"
val gitCommitCount: Int = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitShortSha: String = git("rev-parse", "--short=8", "HEAD") ?: "unknown"
val gitIsDirty: Boolean = git("status", "--porcelain") != null
val appVersionName: String = git("describe", "--tags", "--exact-match", "HEAD")?.removePrefix("v")
    ?: ("$baseVersion-dev+$gitCommitCount.g$gitShortSha" + if (gitIsDirty) ".dirty" else "")

android {
    namespace = "de.linuxundich.hausmusik"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(signingProperties.getValue("HAUSMUSIK_STORE_FILE")!!)
                storePassword = signingProperties.getValue("HAUSMUSIK_STORE_PASSWORD")
                keyAlias = signingProperties.getValue("HAUSMUSIK_KEY_ALIAS")
                keyPassword = signingProperties.getValue("HAUSMUSIK_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "de.linuxundich.hausmusik"
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = appVersionName
        buildConfigField("String", "GIT_COMMIT", "\"$gitShortSha\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE.md", "/META-INF/LICENSE-notice.md")
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    implementation(project(":sonos"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.media3.session)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.palette.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
