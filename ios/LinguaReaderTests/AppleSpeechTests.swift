import XCTest
import LinguaShared

/// Spike de la Fase 1: ¿entregan las voces del sistema rangos por palabra (`willSpeakRangeOfSpeechString`)?
/// En Device Farm se ejecuta en iPhones reales; el número de marcas queda en el log del run.
final class AppleSpeechTests: XCTestCase {

    func testSystemVoicesAreListed() async throws {
        let engine = AppleSpeechEngine()
        let voices = try await engine.listVoices(languageTag: "en-US")
        NSLog("Apple TTS voices for en-US: %@", voices.map { "\($0.displayName) [\($0.languageTag), q=\($0.quality?.intValue ?? 0), \($0.gender)]" }.joined(separator: "; "))
        XCTAssertFalse(voices.isEmpty, "iOS always ships at least one compact English voice")
    }

    func testWordRangesArriveWhileSpeaking() async throws {
        let engine = AppleSpeechEngine()
        let readiness = try await engine.prepare(languageTag: "en-US", voiceId: nil)
        guard readiness is EngineReadinessReady else { throw XCTSkip("No English voice installed: \(readiness)") }

        let done = expectation(description: "utterance finished")
        var marks = 0
        var started = false
        let watcher = LinguaShared.shared.watchSpeechEvents(engine: engine) { event in
            if event is SpeechEventStarted { started = true }
            if event is SpeechEventWordRange { marks += 1 }
            if event is SpeechEventDone || event is SpeechEventError || event is SpeechEventStopped { done.fulfill() }
        }
        engine.enqueue(utteranceId: "spike", text: "The little fox runs through the quiet forest every morning.", speed: 1.0, flush: true)
        await fulfillment(of: [done], timeout: 30)
        watcher.close()
        NSLog("Apple TTS word marks: %d (started=%d)", marks, started ? 1 : 0)
        if !started { throw XCTSkip("The synthesizer never started on this host (no audio route?)") }
        XCTAssertGreaterThan(marks, 0, "iOS voices should report word ranges while speaking")
    }
}
