plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// What CI runs as unit tests (tech plan §24.1): the debug variant of every Android module (the GitHub channel for the
// app) and the tests of the JVM modules, once each.
val unitTests by tasks.registering {
    group = "verification"
    description = "Runs the unit tests of every module the way CI does."
}
subprojects {
    val module = path
    plugins.withId("com.android.library") { unitTests.configure { dependsOn("$module:testDebugUnitTest") } }
    plugins.withId("com.android.application") { unitTests.configure { dependsOn("$module:testGithubDebugUnitTest") } }
    plugins.withId("org.jetbrains.kotlin.jvm") { unitTests.configure { dependsOn("$module:test") } }
}
