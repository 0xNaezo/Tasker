plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.tasker.core.data"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    api(project(":core:parser"))
    implementation(project(":core:database"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.snowball.stemmer)
    api(libs.androidx.paging.runtime)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotest.property)
}
