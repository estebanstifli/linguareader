import XCTest
import LinguaShared

/// Paridad con la JVM: los mismos resultados que los tests de `core` en Android para tokenización y clave de caché,
/// ahora ejecutados en iOS (simulador en CI, iPhone real en Device Farm).
final class SharedParityTests: XCTestCase {

    func testTokenizerSplitsFrenchElisionLikeJvm() {
        let spans = Tokenizer.shared.tokenize(text: "Don't stop l'école", languageTag: "fr")
        XCTAssertEqual(spans.map { [Int($0.start), Int($0.end)] }, [[0, 5], [6, 10], [11, 13], [13, 18]])
    }

    func testTokenizerKeepsAccentsAndApostrophes() {
        let spans = Tokenizer.shared.tokenize(text: "El niño comió «pan»; don't!", languageTag: "es")
        let words = spans.map { span -> String in
            let text = "El niño comió «pan»; don't!"
            let utf16 = text.utf16
            let s = utf16.index(utf16.startIndex, offsetBy: Int(span.start))
            let e = utf16.index(utf16.startIndex, offsetBy: Int(span.end))
            return String(text[s..<e])
        }
        XCTAssertEqual(words, ["El", "niño", "comió", "pan", "don't"])
    }

    func testAudioCacheKeyMatchesJvm() {
        let request = SpeechRequest(text: "Hello  world", languageTag: "en-US", voiceId: nil, speed: 1.0, parameters: [:])
        let key = AudioCacheKey.shared.of(request: request, engineId: "apple", engineVersion: "1")
        // sha256 de "v1\ntext=Hello world\nlang=en-us\nengine=apple@1\nvoice=default\nspeed=1.00\n" (calculado en la JVM).
        XCTAssertEqual(key, "e5983b2e572e0546fd81e09d06cee7c0af5e048b0136d5c980440b2d8b2d46a1")
    }

    func testSentenceSegmentationUsesFoundation() {
        let text = "Hello there. How are you? Fine!"
        let sentences = SentenceSegmenter.shared.split(paragraph: text, languageTag: "en")
        XCTAssertEqual(sentences.count, 3, "\(sentences)")
    }
}
