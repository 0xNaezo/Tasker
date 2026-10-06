plugins {
    alias(libs.plugins.tasker.android.feature)
}

android {
    namespace = "app.tasker.feature.today"
}

dependencies {
    implementation(project(":core:ai"))
}
