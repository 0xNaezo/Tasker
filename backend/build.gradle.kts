plugins {
    alias(libs.plugins.tasker.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    application
}

// AI proxy for Google Play builds (tech plan §18.1). Local run: see backend/README.md.
application {
    mainClass = "app.tasker.backend.ApplicationKt"
    applicationName = "backend"
}

dependencies {
    implementation(project(":core:ai-contract"))
    implementation(project(":core:ai-claude"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.java.jwt)
    implementation(libs.google.auth)
    implementation(libs.hikari)
    implementation(libs.logback.classic)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.h2)
}
