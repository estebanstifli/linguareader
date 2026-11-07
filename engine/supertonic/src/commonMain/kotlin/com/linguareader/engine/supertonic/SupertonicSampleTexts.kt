package com.linguareader.engine.supertonic

/**
 * Frases de muestra en cada uno de los 31 idiomas de Supertonic 3 ([SupertonicModel.LANGUAGES]), en el idioma de la
 * voz y no en el de la interfaz: con ellas se genera el paquete de voces (`VoicePackTest`) y, si el paquete no está,
 * «Probar voz» sintetiza la misma frase con el modelo.
 *
 * - [SAMPLE]: la muestra de «Probar voz».
 * - [READY]: la frase de «Preparando la lectura…» que suena mientras se calienta el motor.
 */
object SupertonicSampleTexts {
    val SAMPLE: Map<String, String> = mapOf(
        "en" to "Hello! This is a short test of the Supertonic voice, running offline on this phone.",
        "ko" to "안녕하세요! 이것은 이 휴대폰에서 오프라인으로 실행되는 슈퍼토닉 음성의 짧은 테스트입니다.",
        "ja" to "こんにちは！これは、この端末でオフラインで動くスーパートニック音声の短いテストです。",
        "ar" to "مرحباً! هذا اختبار قصير لصوت سوبرتونيك، يعمل دون اتصال على هذا الهاتف.",
        "bg" to "Здравейте! Това е кратък тест на гласа Супертоник, който работи офлайн на този телефон.",
        "cs" to "Dobrý den! Toto je krátká zkouška hlasu Supertonic, který běží offline na tomto telefonu.",
        "da" to "Hej! Dette er en kort test af Supertonic-stemmen, der kører offline på denne telefon.",
        "de" to "Hallo! Dies ist ein kurzer Test der Supertonic-Stimme, die offline auf diesem Telefon läuft.",
        "el" to "Γεια σας! Αυτή είναι μια σύντομη δοκιμή της φωνής Supertonic, που λειτουργεί χωρίς σύνδεση σε αυτό το τηλέφωνο.",
        "es" to "¡Hola! Esta es una breve prueba de la voz Supertonic, sin conexión.",
        "et" to "Tere! See on Supertonicu hääle lühike proov, mis töötab selles telefonis võrguühenduseta.",
        "fi" to "Hei! Tämä on lyhyt testi Supertonic-äänestä, joka toimii tässä puhelimessa ilman verkkoyhteyttä.",
        "fr" to "Bonjour ! Ceci est un court essai de la voix Supertonic, sans connexion.",
        "hi" to "नमस्ते! यह सुपरटॉनिक आवाज़ की एक छोटी परीक्षा है, जो इस फ़ोन पर ऑफ़लाइन चल रही है।",
        "hr" to "Pozdrav! Ovo je kratka proba glasa Supertonic koji radi izvanmrežno na ovom telefonu.",
        "hu" to "Üdvözlöm! Ez a Supertonic hang rövid próbája, amely offline fut ezen a telefonon.",
        "id" to "Halo! Ini adalah uji singkat suara Supertonic yang berjalan secara offline di ponsel ini.",
        "it" to "Ciao! Questa è una breve prova della voce Supertonic, che funziona offline su questo telefono.",
        "lt" to "Sveiki! Tai trumpas Supertonic balso bandymas, veikiantis šiame telefone be interneto.",
        "lv" to "Sveiki! Šis ir īss Supertonic balss tests, kas darbojas šajā telefonā bezsaistē.",
        "nl" to "Hallo! Dit is een korte test van de Supertonic-stem, die offline op deze telefoon werkt.",
        "pl" to "Dzień dobry! To krótki test głosu Supertonic, działającego offline na tym telefonie.",
        "pt" to "Olá! Este é um breve teste da voz Supertonic, funcionando sem conexão neste celular.",
        "ro" to "Bună ziua! Acesta este un scurt test al vocii Supertonic, care rulează offline pe acest telefon.",
        "ru" to "Здравствуйте! Это короткая проверка голоса Супертоник, который работает офлайн на этом телефоне.",
        "sk" to "Dobrý deň! Toto je krátka skúška hlasu Supertonic, ktorý beží offline na tomto telefóne.",
        "sl" to "Pozdravljeni! To je kratek preizkus glasu Supertonic, ki deluje brez povezave na tem telefonu.",
        "sv" to "Hej! Det här är ett kort test av Supertonic-rösten, som körs offline på den här telefonen.",
        "tr" to "Merhaba! Bu, bu telefonda çevrimdışı çalışan Supertonic sesinin kısa bir denemesidir.",
        "uk" to "Вітаю! Це коротка перевірка голосу Супертонік, який працює офлайн на цьому телефоні.",
        "vi" to "Xin chào! Đây là bài kiểm tra ngắn giọng đọc Supertonic, chạy ngoại tuyến trên điện thoại này.",
    )

    val READY: Map<String, String> = mapOf(
        "en" to "Getting ready to read…",
        "ko" to "읽기를 준비하고 있습니다…",
        "ja" to "読み上げの準備をしています…",
        "ar" to "جارٍ التحضير للقراءة…",
        "bg" to "Подготовка за четене…",
        "cs" to "Příprava čtení…",
        "da" to "Gør klar til at læse…",
        "de" to "Lesen wird vorbereitet…",
        "el" to "Προετοιμασία της ανάγνωσης…",
        "es" to "Preparando la lectura…",
        "et" to "Lugemise ettevalmistamine…",
        "fi" to "Valmistellaan lukemista…",
        "fr" to "Préparation de la lecture…",
        "hi" to "पढ़ने की तैयारी हो रही है…",
        "hr" to "Priprema čitanja…",
        "hu" to "Az olvasás előkészítése…",
        "id" to "Menyiapkan pembacaan…",
        "it" to "Preparazione della lettura…",
        "lt" to "Ruošiamasi skaityti…",
        "lv" to "Sagatavojas lasīšanai…",
        "nl" to "Het lezen wordt voorbereid…",
        "pl" to "Przygotowanie do czytania…",
        "pt" to "Preparando a leitura…",
        "ro" to "Se pregătește lectura…",
        "ru" to "Подготовка к чтению…",
        "sk" to "Príprava čítania…",
        "sl" to "Priprava branja…",
        "sv" to "Förbereder läsningen…",
        "tr" to "Okuma hazırlanıyor…",
        "uk" to "Підготовка до читання…",
        "vi" to "Đang chuẩn bị đọc…",
    )

    /** Frase de muestra para [languageTag] (subetiqueta primaria), o la inglesa si el idioma no está. */
    fun sample(languageTag: String): String = SAMPLE[languageTag.substringBefore('-')] ?: SAMPLE.getValue("en")
}
