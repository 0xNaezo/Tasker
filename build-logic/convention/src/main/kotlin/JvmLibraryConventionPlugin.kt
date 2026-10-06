import app.tasker.buildlogic.configureJava
import app.tasker.buildlogic.configureKotlin
import app.tasker.buildlogic.configureQuality
import app.tasker.buildlogic.configureTests
import app.tasker.buildlogic.lib
import app.tasker.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin/JVM module without the Android SDK (core:model, core:domain, core:parser, core:ai-contract). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            configureJava()
            configureKotlin()
            configureTests()
            configureQuality()
            dependencies {
                add("testImplementation", libs.lib("junit"))
                add("testImplementation", libs.lib("truth"))
                add("testImplementation", libs.lib("kotest-property"))
                add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            }
        }
    }
}
