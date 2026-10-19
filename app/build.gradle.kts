plugins {
    alias(libs.plugins.android.application)
    // AGP 9 incluye Kotlin integrado: no se aplica org.jetbrains.kotlin.android.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.linguareader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.linguareader.app"
        minSdk = 26 // UtteranceProgressListener.onRangeStart (eventos por palabra)
        targetSdk = 36
        // Paquetes descargables: adjuntos de una release de GitHub (docs/ARCHITECTURE.md → Paquetes).
        val packsRepo = providers.gradleProperty("packsRepo").getOrElse("estebanstifli/linguareader")
        val packsTag = providers.gradleProperty("packsTag").getOrElse("packs-v1")
        buildConfigField("String", "PACKS_REPO", "\"$packsRepo\"")
        buildConfigField("String", "PACKS_TAG", "\"$packsTag\"")
        // Bibliotecas EPUB por idioma (tools/build_library.py): otra release del mismo repositorio.
        val libraryTag = providers.gradleProperty("libraryTag").getOrElse("library-v1")
        buildConfigField("String", "LIBRARY_TAG", "\"$libraryTag\"")
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "com.linguareader.HiltTestRunner"

        // Arquitecturas: solo móviles de 64 bits y el emulador (ver `splits` más abajo). ONNX Runtime
        // aporta ~34 MB nativos por ABI y un modelo de ~400 MB en fp32 no es práctico en 32 bits.
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Un APK por arquitectura: el móvil (arm64-v8a) no carga con las librerías nativas del emulador
    // (x86_64) de ONNX Runtime y ML Kit, ~57 MB. installDebug elige el adecuado al dispositivo.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            // Tablas de criptografía post-cuántica de BouncyCastle (dependencia de PdfBox para PDF
            // cifrados). PdfBox no usa Picnic ni SIKE; son ~8 MB de datos.
            excludes += setOf("org/bouncycastle/pqc/crypto/picnic/**", "org/bouncycastle/pqc/crypto/sike/**")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine:supertonic"))
    implementation(project(":engine:alignment"))
    implementation(project(":importer"))
    implementation(libs.jsoup) // páginas web compartidas (BookImportService.readUrl)
    implementation(libs.pdfbox.android) // importación de PDF con capa de texto
    implementation(libs.mlkit.translate) // traducción automática en el dispositivo
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.media3.exoplayer)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Espresso >= 3.7 es necesario en API 36 (InputManager.getInstance ya no existe).
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
