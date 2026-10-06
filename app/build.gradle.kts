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
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "channel"
    productFlavors {
        create("github") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"github\"")
        }
        create("play") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"play\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
}
