plugins {
    alias(libs.plugins.tasker.jvm.library)
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
}

// detekt runs these rules with its own embedded Kotlin 2.0 runtime.
configurations.configureEach {
    if (name.startsWith("test")) {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-compiler")) {
                useVersion("2.0.21")
            }
        }
    }
}
