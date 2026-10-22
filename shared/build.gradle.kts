import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

/*
 * Paraguas de los módulos compartidos para la app iOS (`ios/`): un único framework `LinguaShared` que exporta
 * `:core`, `:data`, `:importer` y `:engine:*`, más los adaptadores de Apple que solo tienen sentido en iOS
 * (`src/iosMain`: voz del sistema con AVSpeechSynthesizer, repositorio de libros en disco, importador, arranque
 * del grafo Koin y helpers para observar Flows desde Swift).
 *
 * - En Windows/Linux solo se compilan las klib (`./gradlew :shared:compileKotlinIosArm64`).
 * - El XCFramework lo enlaza un Mac (GitHub Actions, `.github/workflows/ios.yml`):
 *   `./gradlew :shared:assembleLinguaSharedDebugXCFramework` → `shared/build/XCFrameworks/debug/LinguaShared.xcframework`.
 * Sin target jvm: Android no consume este módulo.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    val xcf = XCFramework("LinguaShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "LinguaShared"
            // Dinámico: Kotlin/Native enlaza él mismo las librerías del sistema y de cinterop (CoreCrypto, SQLite
            // empaquetado…); con uno estático habría que repetir esos flags en Xcode.
            isStatic = false
            export(project(":core"))
            export(project(":data"))
            export(project(":domain"))
            export(project(":importer"))
            export(project(":engine:onnx"))
            export(project(":engine:alignment"))
            export(project(":engine:supertonic"))
            xcf.add(this)
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":data"))
            api(project(":domain"))
            api(project(":importer"))
            api(project(":engine:onnx"))
            api(project(":engine:alignment"))
            api(project(":engine:supertonic"))
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.atomicfu)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}
