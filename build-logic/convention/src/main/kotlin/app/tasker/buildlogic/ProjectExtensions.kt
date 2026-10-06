package app.tasker.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String) = findLibrary(alias).orElseThrow {
    IllegalArgumentException("Library '$alias' is missing from gradle/libs.versions.toml")
}

fun VersionCatalog.pluginId(alias: String): String = findPlugin(alias).orElseThrow {
    IllegalArgumentException("Plugin '$alias' is missing from gradle/libs.versions.toml")
}.get().pluginId

/**
 * SDK levels from tech plan §5: minSdk 26 (never above 33), targetSdk 36. compileSdk only selects the
 * API surface we build against (current AndroidX requires 37) and does not affect installation.
 */
object Sdk {
    const val COMPILE = 37
    const val MIN = 26
    const val TARGET = 36
}
