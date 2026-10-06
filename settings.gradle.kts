pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "Tasker"

include(":app")
include(":core:model")
include(":core:domain")
include(":core:testing")
include(":core:database")
include(":core:parser")
include(":core:data")
include(":core:designsystem")
include(":core:ui")
include(":core:ai-contract")
include(":core:ai-claude")
include(":core:backup")
include(":feature:capture")
include(":feature:today")
include(":feature:inbox")
include(":feature:tasks")
include(":feature:task")
include(":feature:review")
include(":feature:settings")
include(":lint:detekt-rules")
include(":backend")
include(":tools:ai-eval")
