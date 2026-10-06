plugins {
    alias(libs.plugins.tasker.android.feature)
}

android {
    namespace = "app.tasker.feature.settings"
}

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:backup"))
    implementation(project(":core:calendar"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
}
