import app.tasker.buildlogic.lib
import app.tasker.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Feature modules (tech plan §6): no dependencies between features; navigation is assembled in `app`;
 * the database is reachable only through `core:data`.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("tasker.android.library")
            pluginManager.apply("tasker.android.compose")
            pluginManager.apply("tasker.android.hilt")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
            dependencies {
                add("implementation", project(":core:model"))
                add("implementation", project(":core:domain"))
                add("implementation", project(":core:data"))
                add("implementation", project(":core:designsystem"))
                add("implementation", project(":core:ui"))
                add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
                add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
                add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
                add("implementation", libs.lib("androidx-navigation3-runtime"))
                add("implementation", libs.lib("kotlinx-serialization-json"))
                add("implementation", libs.lib("androidx-compose-material-icons-extended"))
                add("testImplementation", project(":core:testing"))
            }
        }
    }
}
