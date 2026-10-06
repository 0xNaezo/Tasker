plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.compose)
    alias(libs.plugins.tasker.android.hilt)
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
}
