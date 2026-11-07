import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 incluye Kotlin integrado: no se aplica org.jetbrains.kotlin.android.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.linguareader"
    compileSdk = 37

    defaultConfig {
        // Identificador en Google Play: no se puede cambiar una vez publicada la app.
        applicationId = "com.lingoaudiobook.app"
        minSdk = 26 // UtteranceProgressListener.onRangeStart (eventos por palabra)
        targetSdk = 36
        // Paquetes descargables: ficheros estáticos en <base>/<prefijo>/ (bucket R2 público; docs/ARCHITECTURE.md → Paquetes).
        val packsBaseUrl = providers.gradleProperty("packsBaseUrl").getOrElse("https://files.lingoaudiobook.eu")
        val packsTag = providers.gradleProperty("packsTag").getOrElse("packs-v1")
        buildConfigField("String", "PACKS_BASE_URL", "\"$packsBaseUrl\"")
        buildConfigField("String", "PACKS_TAG", "\"$packsTag\"")
        // Bibliotecas EPUB por idioma y narraciones (tools/build_library.py): otro prefijo del mismo origen.
        val libraryTag = providers.gradleProperty("libraryTag").getOrElse("library-v1")
        buildConfigField("String", "LIBRARY_TAG", "\"$libraryTag\"")
        // Cada subida a Play necesita un versionCode mayor que el anterior.
        versionCode = 6
        versionName = "0.4.2"
        testInstrumentationRunner = if (providers.gradleProperty("storeCapture").orNull == "true")
            "androidx.test.runner.AndroidJUnitRunner" else "com.linguareader.KoinTestRunner"

        // Arquitecturas: solo móviles de 64 bits y el emulador (ver `splits` más abajo). ONNX Runtime
        // aporta ~34 MB nativos por ABI y un modelo de ~400 MB en fp32 no es práctico en 32 bits.
    }

    // Idiomas de la interfaz: values/ (inglés, base) + values-xx/. Genera locales_config.xml para el
    // «Idioma de la app» de Android 13+ (docs/I18N.md).
    androidResources {
        generateLocaleConfig = true
    }

    // Firma de release (clave de subida de Play App Signing). Los datos están en keystore.properties, fuera de git;
    // sin ese fichero, la release se genera sin firmar (docs/PLAY_STORE.md › Firma).
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Pseudoidiomas en-XA / ar-XB para ver textos fijos en el código y textos que se cortan.
            isPseudoLocalesEnabled = true
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

dependencies {
    implementation(project(":core"))
    implementation(project(":data")) // Room y preferencias (KMP)
    implementation(project(":domain")) // gestores compartidos: libros importados, bibliotecas, packs, narraciones
    implementation(project(":engine:supertonic"))
    implementation(project(":importer"))
    implementation(libs.ksoup) // páginas web compartidas (BookImportService.readUrl), mismo parser que el importador
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

    implementation(libs.androidx.room.runtime) // Room.databaseBuilder(context, …): la base viene de :data

    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test) // verificación del grafo (KoinModulesTest)
    testImplementation(kotlin("reflect"))
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    // Alineadores históricos solo en el APK de pruebas para conservar los benchmarks; nunca en la app.
    androidTestImplementation(project(":engine:alignment"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Espresso >= 3.7 es necesario en API 36 (InputManager.getInstance ya no existe).
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
