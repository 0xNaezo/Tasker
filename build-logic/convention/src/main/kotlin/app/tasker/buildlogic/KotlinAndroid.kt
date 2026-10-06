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
    // Gradle 9 fails a test task that finds no tests. Hilt generates test sources even for a module without tests,
    // which is no misconfiguration; where tests exist, the check stays on.
    val hasTests = file("src/test").exists()
    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests.set(hasTests)
        maxHeapSize = "2g"
        systemProperty("robolectric.logging.enabled", "false")
        // Robolectric downloads android-all jars at runtime; Maven Central rate-limits CI, the Google mirror does not.
        systemProperty("robolectric.dependency.repo.url", MAVEN_MIRROR)
        systemProperty("robolectric.dependency.repo.id", "google-maven-central-mirror")
        // The Android 16 sandbox reaches into JDK internals on JDK 21.
        jvmArgs(
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
        )
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

private const val MAVEN_MIRROR = "https://maven-central.storage-download.googleapis.com/maven2/"
