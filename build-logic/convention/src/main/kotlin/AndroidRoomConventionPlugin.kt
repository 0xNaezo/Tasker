import androidx.room.gradle.RoomExtension
import app.tasker.buildlogic.lib
import app.tasker.buildlogic.libs
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Room with exported schemas (tech plan §7.9): every migration is tested against them. */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("androidx.room")
            pluginManager.apply("com.google.devtools.ksp")
            extensions.configure<RoomExtension> {
                schemaDirectory("$projectDir/schemas")
            }
            extensions.configure<KspExtension> {
                arg("room.generateKotlin", "true")
            }
            dependencies {
                add("implementation", libs.lib("androidx-room-runtime"))
                add("implementation", libs.lib("androidx-room-paging"))
                add("ksp", libs.lib("androidx-room-compiler"))
                add("testImplementation", libs.lib("androidx-room-testing"))
                add("androidTestImplementation", libs.lib("androidx-room-testing"))
            }
        }
    }
}
