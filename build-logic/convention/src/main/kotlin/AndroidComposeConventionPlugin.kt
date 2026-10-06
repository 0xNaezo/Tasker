import app.tasker.buildlogic.lib
import app.tasker.buildlogic.libs
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.getByType(CommonExtension::class.java).buildFeatures.compose = true
            extensions.configure<ComposeCompilerGradlePluginExtension> {
                stabilityConfigurationFiles.add(
                    rootProject.layout.projectDirectory.file("config/compose/stability.conf"),
                )
            }
            dependencies {
                val bom = platform(libs.lib("androidx-compose-bom"))
                add("implementation", bom)
                add("testImplementation", bom)
                add("androidTestImplementation", bom)
                add("implementation", libs.lib("androidx-compose-runtime"))
                add("implementation", libs.lib("androidx-compose-ui"))
                add("implementation", libs.lib("androidx-compose-foundation"))
                add("implementation", libs.lib("androidx-compose-material3"))
                add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
                add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
                add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))
                add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
                add("androidTestImplementation", libs.lib("androidx-compose-ui-test-junit4"))
            }
        }
    }
}
