plugins {
    alias(libs.plugins.tasker.android.feature)
}

android {
    namespace = "app.tasker.feature.search"
}

dependencies {
    implementation(libs.androidx.paging.compose)
}
