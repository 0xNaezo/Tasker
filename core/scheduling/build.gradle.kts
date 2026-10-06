plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.hilt)
}

android {
    namespace = "app.tasker.core.scheduling"
}

dependencies {
    // Public signatures use the gate's types (FlushResult, QuietHoursAlarm); core:data comes with it.
    api(project(":core:notifications"))
    // The app implements Configuration.Provider with HiltWorkerFactory, so both are part of this module's API.
    api(libs.androidx.work.runtime)
    api(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:database"))
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.datastore)
    testImplementation(libs.androidx.work.testing)
}
