import androidx.room.gradle.RoomExtension
import app.tasker.buildlogic.lib
import app.tasker.buildlogic.libs
import com.android.build.api.variant.HasUnitTest
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

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
            // The Room plugin hands the schemas to device tests only; JVM tests check them with MigrationTestHelper too.
            extensions.configure<LibraryAndroidComponentsExtension> {
                onVariants { variant ->
                    (variant as? HasUnitTest)?.unitTest?.sources?.assets?.addStaticSourceDirectory("$projectDir/schemas")
                }
            }
            // Test tasks do not track asset contents: without this, a changed schema would not run them again.
            tasks.withType<Test>().configureEach {
                inputs.dir("$projectDir/schemas").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("roomSchemas")
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
