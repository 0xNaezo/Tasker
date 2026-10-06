package app.tasker.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * ktlint, detekt and the project's own detekt rule set (tech plan §5 "Качество кода"):
 * `LocalDate.now()`, `Instant.now()` and friends are forbidden outside the injectable clock.
 */
internal fun Project.configureQuality() {
    pluginManager.apply(libs.pluginId("ktlint"))
    extensions.configure<KtlintExtension> {
        version.set(libs.findVersion("ktlint").get().requiredVersion)
        android.set(true)
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    pluginManager.apply(libs.pluginId("detekt"))
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom(
            "src/main/kotlin",
            "src/main/java",
            "src/github/kotlin",
            "src/play/kotlin",
            "src/debug/kotlin",
            "src/release/kotlin",
        )
    }
    // detekt 1.23 embeds Kotlin 2.0.21; keep its own classpath on that version.
    configurations.matching { it.name == "detekt" || it.name == "detektPlugins" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
        }
    }
    if (path != ":lint:detekt-rules") {
        dependencies { add("detektPlugins", project(":lint:detekt-rules")) }
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(false)
            txt.required.set(false)
            md.required.set(false)
        }
    }
}
