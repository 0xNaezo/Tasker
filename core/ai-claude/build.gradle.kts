plugins {
    alias(libs.plugins.tasker.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Claude API runner for the AI routes: used by the backend proxy and by the app's direct mode (tech plan §17.2, §17.4).
dependencies {
    api(project(":core:ai-contract"))
    api(libs.anthropic.java)
}
