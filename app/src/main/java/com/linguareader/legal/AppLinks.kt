package com.linguareader.legal

/**
 * Web pública de la app y contacto. Usar PRIVACY_POLICY también en Play Console.
 */
object AppLinks {
    const val WEBSITE = "https://lingoaudiobook.eu/"
    const val PRIVACY_POLICY = WEBSITE + "privacy/"
    const val HELP = WEBSITE + "help/"
    const val CONTACT_EMAIL = "wpbotwriter@gmail.com"
}

/** Componente de terceros incluido en la app o descargado por ella, con su licencia. */
data class ThirdPartyNotice(val name: String, val author: String, val license: String, val url: String)

/**
 * Avisos de terceros para Ajustes › Acerca de. Apache-2.0, MIT y SIL OFL exigen conservar el aviso de copyright y la
 * licencia al redistribuir. Mantener al día con `gradle/libs.versions.toml` y `docs/ARCHITECTURE.md#licencias`.
 */
object ThirdPartyNotices {
    const val APACHE_2 = "Apache License 2.0"
    private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

    val included = listOf(
        ThirdPartyNotice("AndroidX, Jetpack Compose, Room, DataStore, Navigation, Media3", "The Android Open Source Project", APACHE_2, APACHE_URL),
        ThirdPartyNotice("Material Icons", "Google", APACHE_2, APACHE_URL),
        ThirdPartyNotice("Kotlin, kotlinx.coroutines, kotlinx.serialization, atomicfu", "JetBrains", APACHE_2, APACHE_URL),
        ThirdPartyNotice("Koin", "Kotzilla and contributors", APACHE_2, APACHE_URL),
        ThirdPartyNotice("Okio", "Square, Inc.", APACHE_2, APACHE_URL),
        ThirdPartyNotice("Ksoup", "Fleeksoft", "MIT License", "https://github.com/fleeksoft/ksoup/blob/release/LICENSE.md"),
        ThirdPartyNotice("PdfBox-Android", "Tom Roush, The Apache Software Foundation", APACHE_2, APACHE_URL),
        ThirdPartyNotice("Liberation Fonts (PdfBox-Android)", "Red Hat, Inc.", "SIL Open Font License 1.1", "https://openfontlicense.org"),
        ThirdPartyNotice("ONNX Runtime", "Microsoft Corporation", "MIT License", "https://github.com/microsoft/onnxruntime/blob/main/LICENSE"),
        ThirdPartyNotice("Supertonic (reference code)", "Supertone Inc.", "MIT License", "https://github.com/supertone-inc/supertonic"),
        ThirdPartyNotice("ML Kit Translation", "Google", "ML Kit Terms of Service", "https://developers.google.com/ml-kit/terms"),
    )

    /** Modelos que la app no incluye y que el usuario descarga si quiere. */
    val downloadable = listOf(
        ThirdPartyNotice("Supertonic 3 (voice model)", "Supertone Inc.", "BigScience OpenRAIL-M", "https://huggingface.co/supertone-oss-archive/supertonic-3"),
    )
}
