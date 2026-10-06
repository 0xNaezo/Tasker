import app.tasker.buildlogic.Sdk
import app.tasker.buildlogic.configureKotlinAndroid
import app.tasker.buildlogic.configureQuality
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = Sdk.TARGET
            }
            configureQuality()
        }
    }
}
