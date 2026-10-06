plugins {
    alias(libs.plugins.tasker.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    application
}

// Manual AI quality eval (tech plan §17.6). It costs money: run it by hand, never in CI.
//   ANTHROPIC_API_KEY=... ./gradlew :tools:ai-eval:run --args="--limit 20"
// The unit tests run in CI without network: they check the dataset and the report maths.
application {
    mainClass = "app.tasker.tools.aieval.EvalMainKt"
}

dependencies {
    implementation(project(":core:ai-contract"))
    implementation(project(":core:ai-claude"))
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

val evalDataset = rootProject.layout.projectDirectory.file("docs/ai-eval/enrich-v1.jsonl")

tasks.withType<Test>().configureEach {
    inputs.file(evalDataset).withPropertyName("evalDataset")
    systemProperty("tasker.evalDataset", evalDataset.asFile.absolutePath)
}
