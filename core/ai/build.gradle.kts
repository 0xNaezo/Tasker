plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.tasker.core.ai"
}

// AI on the device (tech plan §17.2): AiGateway with the direct (own API key) and proxy (Google Play) modes,
// consent and the switch, API key storage and the background enrichment queue (CAP-7).
dependencies {
    api(project(":core:data"))
    api(project(":core:ai-contract"))
    implementation(project(":core:ai-openrouter"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.tink.android)
    implementation(libs.play.integrity)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:database"))
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.datastore)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.ktor.client.mock)
}
