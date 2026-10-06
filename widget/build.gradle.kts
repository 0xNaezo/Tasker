plugins {
    alias(libs.plugins.tasker.android.library)
    alias(libs.plugins.tasker.android.compose)
    alias(libs.plugins.tasker.android.hilt)
}

android {
    namespace = "app.tasker.widget"
}

// Home screen widget (tech plan §13): today's plan and one-tap capture. Depends on feature:capture only for its
// entry intents and on core:notifications for deep links; like the app, it is an assembly point, not a feature.
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:notifications"))
    implementation(project(":feature:capture"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
}

// The render test saves the widget's layouts here (reviewed by eye, not compared).
tasks.withType<Test>().configureEach {
    systemProperty("tasker.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.path)
}
