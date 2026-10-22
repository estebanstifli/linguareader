import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Motor Supertonic 3 (ONNX, CPU). Kotlin Multiplatform: preprocesado de texto, flujo de inferencia, recorte y WAV
 * son Kotlin común sobre el puerto `:engine:onnx`; la descarga de modelos (HttpURLConnection) queda en jvmMain
 * hasta que iOS tenga su descargador con URLSession en segundo plano.
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Ver libs.versions.toml: en el PC con el JBR de Android Studio solo funciona ORT 1.18 (misma API Java).
            implementation(libs.onnxruntime.jvm.test)
            implementation(libs.junit)
        }
    }
}

/** Ruta del modelo para la prueba funcional en el PC (descargado en .models/). */
val supertonicModelDir = rootProject.layout.projectDirectory
    .dir(".models/supertonic-3/aafc6e32416a594460b32413efc49d7fe4ce6d46").asFile

tasks.named<Test>("jvmTest") {
    // Las pruebas con el modelo real solo se ejecutan si está descargado (si no, se omiten).
    systemProperty("supertonic.modelDir", supertonicModelDir.absolutePath)
    systemProperty("supertonic.outDir", layout.buildDirectory.dir("supertonic-smoke").get().asFile.absolutePath)
    maxHeapSize = "3g"
    testLogging {
        showStandardStreams = true
    }
}

fun registerVerificationTest(name: String, description: String, pattern: String, configure: Test.() -> Unit) {
    tasks.register<Test>(name) {
        this.description = description
        group = "verification"
        val jvmTest = tasks.named<Test>("jvmTest").get()
        testClassesDirs = jvmTest.testClassesDirs
        classpath = jvmTest.classpath
        filter { includeTestsMatching(pattern) }
        systemProperty("supertonic.modelDir", supertonicModelDir.absolutePath)
        maxHeapSize = "3g"
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
        configure()
    }
}

/**
 * Prueba funcional en el PC: descarga el modelo si falta (≈398 MB, revisión fijada),
 * sintetiza un texto corto en inglés, guarda el WAV y muestra las métricas.
 *   ./gradlew :engine:supertonic:smokeTest
 */
registerVerificationTest("smokeTest", "Descarga (si falta) Supertonic 3 y sintetiza un texto corto en inglés.", "*SupertonicSmokeTest*") {
    systemProperty("supertonic.outDir", layout.buildDirectory.dir("supertonic-smoke").get().asFile.absolutePath)
    systemProperty("supertonic.download", "true")
}

/** Corpus para comparar alineadores forzados (ver tools/eval_aligners.py). */
registerVerificationTest("alignCorpus", "Sintetiza con Supertonic el corpus de evaluación de alineadores.", "*AlignmentCorpusTest*") {
    systemProperty("supertonic.alignCorpus", "true")
    systemProperty("supertonic.corpusDir", rootProject.layout.projectDirectory.dir(".models/align-corpus").asFile.absolutePath)
}

/** Paquete de voces (muestras y frases de «preparando») → .models/voice-pack/voices.zip. */
registerVerificationTest("voicePack", "Sintetiza las muestras de voz y frases de preparación de cada voz e idioma.", "*VoicePackTest*") {
    systemProperty("supertonic.voicePack", "true")
    systemProperty("supertonic.packDir", rootProject.layout.projectDirectory.dir(".models/voice-pack").asFile.absolutePath)
}
