import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Alineación forzada por palabra (wav2vec2 CTC sobre ONNX). Kotlin Multiplatform: el Viterbi, el remuestreo y la
 * preparación de texto son Kotlin común; la inferencia pasa por el puerto `:engine:onnx`.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":engine:onnx"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Ver libs.versions.toml: en el PC con el JBR de Android Studio solo funciona ORT 1.18 (misma API Java).
            implementation(libs.onnxruntime.jvm.test)
            implementation(libs.junit)
        }
    }
}

val models = rootProject.layout.projectDirectory.dir(".models").asFile

tasks.named<Test>("jvmTest") {
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
    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    filter { includeTestsMatching("*Bench*") }
    systemProperty("align.models", models.absolutePath)
    systemProperty("align.bench", "true")
    listOf("align.int8Only", "align.dirs", "align.threads").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    maxHeapSize = "3g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
