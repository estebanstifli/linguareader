import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    // Misma estrategia que :engine:supertonic: API Java de ONNX Runtime; la aporta onnxruntime-android en la app.
    compileOnly(libs.onnxruntime.jvm)

    testImplementation(libs.onnxruntime.jvm.test)
    testImplementation(libs.junit)
}

val models = rootProject.layout.projectDirectory.dir(".models").asFile

tasks.test {
    maxHeapSize = "3g"
    testLogging { showStandardStreams = true }
}

/**
 * Banco de pruebas de alineación con MMS (ONNX) sobre el corpus de Supertonic:
 *   ./gradlew :engine:supertonic:alignCorpus      (genera .models/align-corpus)
 *   ./gradlew :engine:alignment:alignBench
 */
tasks.register<Test>("alignBench") {
    description = "Mide precisión y velocidad de MMS (ONNX int8/q4) sobre .models/align-corpus."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*Bench*") }
    systemProperty("align.models", models.absolutePath)
    systemProperty("align.bench", "true")
    maxHeapSize = "3g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
