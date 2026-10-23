import XCTest
import LinguaShared

/// Traductor ML Kit por el puente compartido: descarga los modelos en+es (red; ~60 MB) y traduce una frase.
/// En CI corre en el simulador; en Device Farm, en el iPhone real.
final class TranslationTests: XCTestCase {

    func testMlKitTranslatesAfterDownloadingTheModels() async throws {
        let shared = LinguaShared.shared
        guard shared.translationCode(languageTag: "es-ES") == "es" else { throw XCTSkip("No translator wired into LinguaShared") }
        let ready = try await shared.ensureTranslationModels(sourceLanguage: "en-US", targetLanguage: "es").boolValue
        XCTAssertTrue(ready, "The English and Spanish models should download — \(shared.diagnosticsText())")
        let result = try await shared.translateText(text: "The little fox runs through the quiet forest.", sourceLanguage: "en-US", targetLanguage: "es")
        guard let translated = result as? TranslationResultTranslated else {
            return XCTFail("Expected a translation, got \(result) — \(shared.diagnosticsText())")
        }
        NSLog("ML Kit en→es: %@", translated.text)
        XCTAssertFalse(translated.text.isEmpty)
        XCTAssertNotEqual(translated.text.lowercased(), "the little fox runs through the quiet forest.")
        XCTAssertTrue(translated.text.lowercased().contains("zorro") || translated.text.lowercased().contains("bosque"), translated.text)
        // Segunda vez: desde la caché de Room, sin pasar por ML Kit.
        let again = try await shared.translateText(text: "The little fox runs through the quiet forest.", sourceLanguage: "en-US", targetLanguage: "es")
        XCTAssertEqual((again as? TranslationResultTranslated)?.text, translated.text)
    }
}
