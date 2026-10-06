plugins {
    alias(libs.plugins.tasker.android.test)
    alias(libs.plugins.baselineprofile)
}

// Baseline Profile generation and the cold start benchmark (tech plan §20 "Отклик", §24.1). Both drive the release
// build of :app on a device: a Gradle Managed Device in the nightly CI, or a connected phone.
android {
    namespace = "app.tasker.baselineprofile"
    targetProjectPath = ":app"

    flavorDimensions += "channel"
    productFlavors {
        create("github") { dimension = "channel" }
        create("play") { dimension = "channel" }
    }

    testOptions.managedDevices.localDevices {
        create("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
