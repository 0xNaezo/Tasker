package app.tasker.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private val JAVA_VERSION = JavaVersion.VERSION_17

/** Shared Android configuration for application, library and test modules. */
internal fun Project.configureKotlinAndroid(extension: CommonExtension) {
    extension.compileSdk = Sdk.COMPILE
    extension.defaultConfig.minSdk = Sdk.MIN
    extension.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    extension.compileOptions.sourceCompatibility = JAVA_VERSION
    extension.compileOptions.targetCompatibility = JAVA_VERSION
    extension.testOptions.unitTests.isIncludeAndroidResources = true
    extension.testOptions.animationsDisabled = true
    extension.packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/LICENSE*",
        "/META-INF/NOTICE*",
        "/META-INF/DEPENDENCIES",
        "/META-INF/INDEX.LIST",
        "/META-INF/io.netty.versions.properties",
    )
    extension.lint.apply {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        lintConfig = rootProject.file("config/lint/lint.xml")
    }
    configureKotlin()
    configureTests()
}

/** Kotlin compiler options shared by Android and JVM modules. */
internal fun Project.configureKotlin() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }
}

internal fun Project.configureJava() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JAVA_VERSION
        targetCompatibility = JAVA_VERSION
    }
}

internal fun Project.configureTests() {
    tasks.withType<Test>().configureEach {
        maxHeapSize = "2g"
        systemProperty("robolectric.logging.enabled", "false")
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
