plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.compose)
    alias(libs.plugins.tasker.android.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.tasker.core.ui"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    api(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(project(":core:testing"))
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}

// Reference screenshots of the shared components (tech plan §22.1, §24.1): `recordRoborazziDebug` writes them,
// `verifyRoborazziDebug` fails on any visible change. They live next to the test and are reviewed in the PR.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
