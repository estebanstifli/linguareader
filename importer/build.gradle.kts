import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Extractores de texto para importar libros del usuario (TXT, Markdown, HTML, EPUB, FB2, MOBI, DOCX/ODT, RTF, SRT/VTT...).
 * Kotlin Multiplatform: la app Android consume el target jvm (y aquí corren los tests, sin emulador); iOS lo
 * compartirá tal cual. Los formatos que necesitan APIs de plataforma (PDF) van en la app.
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
            implementation(libs.ksoup)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}
