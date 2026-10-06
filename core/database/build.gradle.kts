plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.room)
    alias(libs.plugins.tasker.android.hilt)
}

android {
    namespace = "app.tasker.core.database"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.androidx.room.testing)
}
