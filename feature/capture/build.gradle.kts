plugins {
    alias(libs.plugins.tasker.android.feature)
}

android {
    namespace = "app.tasker.feature.capture"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
