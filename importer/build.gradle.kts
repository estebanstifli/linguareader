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

// Extractores de texto para importar libros del usuario (TXT, Markdown, HTML, EPUB, FB2, SRT/VTT...).
// JVM puro: se prueban sin emulador. Los formatos que necesiten APIs de Android (PDF) van aparte.
dependencies {
    api(project(":core"))
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
