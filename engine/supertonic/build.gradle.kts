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
    // Se compila contra la API Java de ONNX Runtime (ai.onnxruntime.*). En Android la aporta
    // onnxruntime-android (mismo API y versión); en los tests/JVM, el artefacto de escritorio.
    compileOnly(libs.onnxruntime.jvm)

    // Ver libs.versions.toml: en el PC con el JBR de Android Studio solo funciona ORT 1.18 (misma API Java).
    testImplementation(libs.onnxruntime.jvm.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/** Ruta del modelo para la prueba funcional en el PC (descargado en .models/). */
val supertonicModelDir = rootProject.layout.projectDirectory
    .dir(".models/supertonic-3/aafc6e32416a594460b32413efc49d7fe4ce6d46").asFile

tasks.test {
    // Las pruebas con el modelo real solo se ejecutan si está descargado (si no, se omiten).
    systemProperty("supertonic.modelDir", supertonicModelDir.absolutePath)
    systemProperty("supertonic.outDir", layout.buildDirectory.dir("supertonic-smoke").get().asFile.absolutePath)
    maxHeapSize = "3g"
    testLogging {
        showStandardStreams = true
    }
}

/**
 * Prueba funcional en el PC: descarga el modelo si falta (≈398 MB, revisión fijada),
 * sintetiza un texto corto en inglés, guarda el WAV y muestra las métricas.
 *   ./gradlew :engine:supertonic:smokeTest
 */
tasks.register<Test>("smokeTest") {
    description = "Descarga (si falta) Supertonic 3 y sintetiza un texto corto en inglés."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*SupertonicSmokeTest*") }
    systemProperty("supertonic.modelDir", supertonicModelDir.absolutePath)
    systemProperty("supertonic.outDir", layout.buildDirectory.dir("supertonic-smoke").get().asFile.absolutePath)
    systemProperty("supertonic.download", "true")
    maxHeapSize = "3g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

/** Corpus para comparar alineadores forzados (ver tools/eval_aligners.py). */
tasks.register<Test>("alignCorpus") {
    description = "Sintetiza con Supertonic el corpus de evaluación de alineadores."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*AlignmentCorpusTest*") }
    systemProperty("supertonic.modelDir", supertonicModelDir.absolutePath)
    systemProperty("supertonic.alignCorpus", "true")
    systemProperty("supertonic.corpusDir", rootProject.layout.projectDirectory.dir(".models/align-corpus").asFile.absolutePath)
    maxHeapSize = "3g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

/** Paquete de voces (muestras y frases de «preparando») → .models/voice-pack/voices.zip. */
tasks.register<Test>("voicePack") {
    description = "Sintetiza las muestras de voz y frases de preparación de cada voz e idioma."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*VoicePackTest*") }
    systemProperty("supertonic.modelDir", supertonicModelDir.absolutePath)
    systemProperty("supertonic.voicePack", "true")
    systemProperty("supertonic.packDir", rootProject.layout.projectDirectory.dir(".models/voice-pack").asFile.absolutePath)
    maxHeapSize = "3g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
