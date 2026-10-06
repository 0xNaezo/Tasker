plugins {
    alias(libs.plugins.tasker.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin/JVM: shared by the Android app (direct and proxy modes) and the backend (tech plan §6, §17.2).
dependencies {
    api(libs.kotlinx.serialization.json)
}
