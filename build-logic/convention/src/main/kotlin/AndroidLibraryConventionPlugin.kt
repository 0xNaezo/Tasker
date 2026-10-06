import app.tasker.buildlogic.Sdk
import app.tasker.buildlogic.configureKotlinAndroid
import app.tasker.buildlogic.configureQuality
import app.tasker.buildlogic.lib
import app.tasker.buildlogic.libs
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = Sdk.TARGET
                lint.targetSdk = Sdk.TARGET
                defaultConfig.consumerProguardFiles("consumer-rules.pro")
            }
            configureQuality()
            dependencies {
                add("testImplementation", libs.lib("junit"))
                add("testImplementation", libs.lib("truth"))
                add("testImplementation", libs.lib("robolectric"))
                add("testImplementation", libs.lib("androidx-test-core"))
                add("testImplementation", libs.lib("androidx-test-ext-junit"))
                add("testImplementation", libs.lib("kotlinx-coroutines-test"))
                add("testImplementation", libs.lib("turbine"))
                add("androidTestImplementation", libs.lib("androidx-test-runner"))
                add("androidTestImplementation", libs.lib("androidx-test-ext-junit"))
            }
        }
    }
}
