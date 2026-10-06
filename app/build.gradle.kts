plugins {
    alias(libs.plugins.tasker.android.application)
    alias(libs.plugins.tasker.android.compose)
    alias(libs.plugins.tasker.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.tasker"

    defaultConfig {
        // Neutral identifier, not tied to the brand (tech plan §2, decision 7; ADR 0001).
        applicationId = "io.github.oxnaezo.tasks"
        versionCode = (findProperty("tasker.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("tasker.versionName") as String?) ?: "0.1.0"
        // Crash reports go nowhere unless a DSN is given at build time and the user agreed (ADR 0004).
        buildConfigField("String", "SENTRY_DSN", "\"${findProperty("tasker.sentryDsn") ?: ""}\"")
        // Google Cloud project for Play Integrity (proxy mode); 0 means "not set".
        buildConfigField("long", "PLAY_CLOUD_PROJECT", "${findProperty("tasker.playCloudProject") ?: 0}L")
        buildConfigField("String", "AI_PROXY_DEV_KEY", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Per-app language picker (Android 13+) lists exactly the shipped translations.
        generateLocaleConfig = true
    }

    flavorDimensions += "channel"
    productFlavors {
        // AI access follows the install channel (tech plan §17.2, §24.1): own API key outside Google Play,
        // the app's proxy with Play Integrity inside it.
        create("github") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"github\"")
            buildConfigField("String", "AI_PROXY_URL", "\"\"")
        }
        create("play") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"play\"")
            buildConfigField("String", "AI_PROXY_URL", "\"${findProperty("tasker.aiProxyUrl") ?: ""}\"")
        }
    }

    buildTypes {
        debug {
            // The proxy's dev environment accepts a shared key instead of Play Integrity (§18.1); never in release.
            buildConfigField("String", "AI_PROXY_DEV_KEY", "\"${findProperty("tasker.aiProxyDevKey") ?: ""}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:ai"))
    implementation(project(":core:backup"))
    implementation(project(":core:calendar"))
    implementation(project(":core:notifications"))
    implementation(project(":core:scheduling"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:today"))
    implementation(project(":feature:inbox"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:task"))
    implementation(project(":feature:review"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:done"))
    implementation(project(":feature:search"))
    implementation(project(":feature:journal"))
    implementation(project(":widget"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sentry.android)

    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
}

// The smoke test saves screenshots of the main screens here (reviewed by eye, not compared).
tasks.withType<Test>().configureEach {
    systemProperty("tasker.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.path)
}
