plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.hilt)
}

android {
    namespace = "app.tasker.core.calendar"
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.datastore)
}
