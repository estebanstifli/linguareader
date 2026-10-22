import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Puerto mínimo de ONNX Runtime para los motores compartidos (alineación wav2vec2, Supertonic):
 * sesiones, tensores float/int64 e inferencia. La UI y los motores no ven `ai.onnxruntime`.
 * - jvm: API Java de ONNX Runtime (en Android la aporta onnxruntime-android; en el PC, el artefacto de escritorio).
 * - iOS: pendiente de enlazar la API C (onnxruntime-c) con cinterop; por ahora compila con un stub que avisa.
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

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        jvmMain.dependencies {
            compileOnly(libs.onnxruntime.jvm)
        }
    }
}
