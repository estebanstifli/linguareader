import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Módulo compartido (Kotlin Multiplatform): modelos, tokenización, controladores de lectura, caché de audio...
 * - jvm: lo consume la app Android (mismo bytecode que antes) y aquí corren los tests.
 * - iosArm64 / iosSimulatorArm64: futura app iOS (ver docs/ARCHITECTURE.md). En Windows/Linux solo se
 *   compilan las klib (no se enlaza ni se prueba); los tests en simulador requieren un Mac.
 * Sin `android.*` ni `java.*` en commonMain: lo que depende de la plataforma va en `core.platform` (expect/actual).
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
        // Clases expect/actual (Sha256): API en beta pero estable en la práctica; evita el aviso en cada build.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.okio)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.icu4j)
        }
    }
}
