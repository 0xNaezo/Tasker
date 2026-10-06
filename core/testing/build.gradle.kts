plugins {
    alias(libs.plugins.tasker.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    api(libs.junit)
    api(libs.truth)
    api(libs.kotlinx.coroutines.test)
}
