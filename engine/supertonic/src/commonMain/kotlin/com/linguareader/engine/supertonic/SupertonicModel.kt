package com.linguareader.engine.supertonic


/** Un fichero del modelo con tamaño y sha256 fijados (verificación de integridad). */
data class ModelFile(val path: String, val sizeBytes: Long, val sha256: String)

/**
 * Supertonic 3 tal y como lo publica el archivo oficial en Hugging Face, fijado a una revisión
 * inmutable. Código de ejemplo: MIT. Pesos: BigScience OpenRAIL-M (ver [LICENSE_URL]).
 */
object SupertonicModel {
    const val ENGINE_ID = "supertonic-3"
    const val DISPLAY_NAME = "Supertonic 3 (local, ONNX)"
    const val REPO = "supertone-oss-archive/supertonic-3"
    const val REVISION = "aafc6e32416a594460b32413efc49d7fe4ce6d46"
    const val BASE_URL = "https://huggingface.co/$REPO/resolve/$REVISION"
    const val LICENSE_URL = "https://huggingface.co/$REPO/blob/$REVISION/LICENSE"
    const val LICENSE_NAME = "BigScience OpenRAIL-M"
    const val MARKER = ".verified"

    /** 31 idiomas de Supertonic 3. */
    val LANGUAGES = setOf(
        "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
        "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
    )

    val VOICES = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")
    const val DEFAULT_VOICE = "F1"

    val FILES = listOf(
        ModelFile("LICENSE", 15_007, "0d944a9110fed9a9602d60e0423a272903e7bd21ab060490774efc77c2275e9f"),
        ModelFile("onnx/tts.json", 8_253, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        ModelFile("onnx/unicode_indexer.json", 277_676, "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f"),
        ModelFile("onnx/duration_predictor.onnx", 3_700_147, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        ModelFile("onnx/text_encoder.onnx", 36_416_150, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        ModelFile("onnx/vector_estimator.onnx", 256_534_781, "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c"),
        ModelFile("onnx/vocoder.onnx", 101_424_195, "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba"),
        ModelFile("voice_styles/F1.json", 292_046, "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2"),
        ModelFile("voice_styles/F2.json", 292_423, "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6"),
        ModelFile("voice_styles/F3.json", 290_794, "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab"),
        ModelFile("voice_styles/F4.json", 291_808, "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b"),
        ModelFile("voice_styles/F5.json", 291_479, "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d"),
        ModelFile("voice_styles/M1.json", 291_748, "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b"),
        ModelFile("voice_styles/M2.json", 292_055, "b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50"),
        ModelFile("voice_styles/M3.json", 290_198, "ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b"),
        ModelFile("voice_styles/M4.json", 291_522, "ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303"),
        ModelFile("voice_styles/M5.json", 291_469, "dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2"),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.sizeBytes }

    /**
     * Versión para la clave de caché: cambia si cambian los pesos o el posprocesado del audio
     * (`trim1` = silencios iniciales/finales recortados).
     */
    val MODEL_VERSION: String = "s3@${REVISION.take(12)}-trim1"
}
