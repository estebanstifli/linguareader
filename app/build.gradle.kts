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
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "com.linguareader.HiltTestRunner"

        // ONNX Runtime (Supertonic) aporta ~34 MB nativos por ABI: solo móviles de 64 bits y el emulador.
        // Un modelo de ~400 MB en fp32 no es práctico en dispositivos de 32 bits.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine:supertonic"))
    implementation(project(":engine:alignment"))
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
