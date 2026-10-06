import app.tasker.buildlogic.Sdk
import app.tasker.buildlogic.configureKotlinAndroid
import app.tasker.buildlogic.configureQuality
import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Test-only modules that drive the app on a device: Baseline Profile generation and Macrobenchmark. */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")
            extensions.configure<TestExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = Sdk.TARGET
                lint.targetSdk = Sdk.TARGET
            }
            configureQuality()
        }
    }
}
