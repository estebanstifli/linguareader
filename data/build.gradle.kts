import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Persistencia compartida (Kotlin Multiplatform): base de datos Room (progreso, marcadores, vocabulario, caché de
 * traducciones, estanterías, papelera, correcciones de libros, documentos del dispositivo) y preferencias del usuario
 * sobre DataStore. Room y DataStore son multiplataforma desde 2.7 y 1.1: entidades, DAOs y migraciones viven en
 * commonMain y el compilador de Room genera la implementación para cada destino (`kspJvm`, `kspIos*`).
 * - jvm: lo consume la app Android (abre la base con el SQLite del sistema, como antes) y aquí corren los tests con el
 *   SQLite empaquetado (`sqlite-bundled`), sin emulador.
 * - iOS: misma base y mismas preferencias con `BundledSQLiteDriver` y `PreferenceDataStoreFactory.createWithPath`
 *   (pendiente de validar en un Mac).
 * Quién crea el `DataStore` y con qué fichero es cosa de cada plataforma (en Android, `AndroidDataStores.kt`).
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
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
        // `expect object AppDatabaseConstructor` (Room genera el `actual` de cada destino).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.androidx.room.runtime)
            api(libs.androidx.datastore.preferences.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

room {
    // Esquemas exportados por versión (automigraciones). Misma carpeta que tenía la app: el hash de identidad no cambia.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
