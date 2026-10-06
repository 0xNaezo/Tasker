plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.compose)
}

android {
    namespace = "app.tasker.core.designsystem"
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.ui.tooling.preview)
}
