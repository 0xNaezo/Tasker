plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.hilt)
}

android {
    namespace = "app.tasker.core.backup"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:database"))
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.datastore)
    testImplementation(libs.androidx.work.testing)
}
