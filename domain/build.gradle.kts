import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Gestores compartidos (Kotlin Multiplatform) que antes vivían en la app Android y solo usaban ficheros, descargas
 * y logs: libros importados, catálogo compuesto, estanterías y papelera, bibliotecas descargables, packs
 * (Supertonic, alineadores, voces pregeneradas), narraciones y pretraducción. Android e iOS construyen las mismas
 * clases con sus rutas (`okio.Path`), su log y sus catálogos JSON; lo específico de cada plataforma (SAF, intents,
 * ML Kit, PDF…) queda fuera, en adaptadores. Los paquetes conservan sus nombres originales para no tocar la UI.
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
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":data"))
            api(project(":importer"))
            api(project(":engine:supertonic"))
            api(project(":engine:alignment"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.okio.fakefilesystem)
        }
    }
}
