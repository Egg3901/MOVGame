plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val playPublicKey = providers.gradleProperty("MOV_PLAY_PUBLIC_KEY")
    .orElse(providers.environmentVariable("MOV_PLAY_PUBLIC_KEY"))
    .orElse("")
val sentryDsn = providers.gradleProperty("MOV_SENTRY_DSN")
    .orElse(providers.environmentVariable("MOV_SENTRY_DSN"))
    .orElse("")

// Native Android UI backed by the shared KMP simulation.
android {
    namespace = "com.lakesidegames.electioneer"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lakesidegames.electioneer"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Empty until the store and crash reporting are configured.
        buildConfigField("String", "PLAY_PUBLIC_KEY", playPublicKey.get().asBuildConfigString())
        buildConfigField("String", "SENTRY_DSN", sentryDsn.get().asBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test-junit"))
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.icons.core)
    implementation(libs.play.billing)
    // Crash reporting (#24): inert until SENTRY_DSN is set at build time.
    implementation(libs.sentry.android)
}
