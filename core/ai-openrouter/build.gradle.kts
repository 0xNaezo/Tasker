plugins {
    alias(libs.plugins.tasker.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// OpenRouter runner for the AI routes: used by the backend proxy, the app's direct mode and the eval (ADR 0011).
// The caller brings the Ktor engine: OkHttp in the app, the backend and the eval, MockEngine in tests.
dependencies {
    api(project(":core:ai-contract"))
    api(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.client.mock)
}
