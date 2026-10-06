plugins {
    alias(libs.plugins.tasker.android.application)
    alias(libs.plugins.tasker.android.compose)
    alias(libs.plugins.tasker.android.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

/** The release key stays out of the repository (tech plan §24.3): CI passes it in environment variables. */
val releaseKeystore: String? = providers.environmentVariable("TASKER_KEYSTORE_FILE").orNull

/** Task texts and weekly statistics pass through the AI proxy, so only over TLS. */
val aiProxyUrl: String = (findProperty("tasker.aiProxyUrl") as String?).orEmpty()
require(aiProxyUrl.isEmpty() || aiProxyUrl.startsWith("https://")) { "tasker.aiProxyUrl must start with https://" }

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
        // Instrumented e2e tests run on the Hilt test application, each test on a clean app (orchestrator).
        testInstrumentationRunner = "app.tasker.TaskerTestRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        // The nightly CI runs the e2e tests on the minimum, the owner's phone and the target API (tech plan §22.1).
        managedDevices {
            localDevices {
                create("api26") {
                    device = "Pixel 2"
                    apiLevel = 26
                    systemImageSource = "aosp"
                }
                create("api33") {
                    device = "Pixel 6"
                    apiLevel = 33
                    systemImageSource = "aosp-atd"
                }
                create("api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "google"
                }
            }
            groups {
                create("nightly") {
                    targetDevices.add(localDevices.getByName("api26"))
                    targetDevices.add(localDevices.getByName("api33"))
                    targetDevices.add(localDevices.getByName("api36"))
                }
            }
        }
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
            buildConfigField("String", "AI_PROXY_URL", "\"$aiProxyUrl\"")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("TASKER_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TASKER_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("TASKER_KEY_PASSWORD").get()
            }
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
            // Without the key (local builds) the release APK stays unsigned.
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

baselineProfile {
    // Profiles are generated on a managed device in the nightly CI and committed, not on every build.
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
}

dependencies {
    baselineProfile(project(":baselineprofile"))
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
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sentry.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    kspAndroidTest(libs.hilt.compiler)
    androidTestUtil(libs.androidx.test.orchestrator)

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
