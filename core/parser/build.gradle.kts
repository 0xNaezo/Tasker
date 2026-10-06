plugins {
    alias(libs.plugins.tasker.jvm.library)
}

dependencies {
    implementation(project(":core:model"))
}
