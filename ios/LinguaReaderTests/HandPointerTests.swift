import XCTest
@testable import LinguaReader

/// Mano que señala la palabra: el dibujo va en el paquete y el seguimiento se coloca sobre la palabra, se adelanta
/// hacia la siguiente y converge sin rebote (misma lógica que `FingerPointerOverlay` en Android).
final class HandPointerTests: XCTestCase {

    func testHandImageIsBundled() {
        XCTAssertNotNil(HandPointerView.loadImage(), "Resources/reading_hand.webp should be in the app bundle")
    }

    func testFirstFrameSnapsToTheWordSlightlyAhead() {
        let motion = HandMotion(initialSpeed: 0.14)
        let word = CGRect(x: 100, y: 200, width: 60, height: 20)
        let next = CGRect(x: 170, y: 200, width: 40, height: 20)
        let inputs = HandMotion.Inputs(box: word, lead: next, step: PointerStep(tokenId: "t1", gap: false), reduceMotion: false, gap: 2)
        XCTAssertTrue(motion.frame(inputs, dt: 0, playing: true))
        XCTAssertTrue(motion.ready)
        XCTAssertGreaterThan(motion.x, word.minX)
        XCTAssertLessThan(motion.x, next.minX)
        XCTAssertEqual(motion.y, word.maxY + 2, accuracy: 0.01)
    }

    func testReduceMotionJumpsWithoutAnimating() {
        let motion = HandMotion(initialSpeed: 0.14)
        let word = CGRect(x: 100, y: 200, width: 60, height: 20)
        let next = CGRect(x: 170, y: 200, width: 40, height: 20)
        var inputs = HandMotion.Inputs(box: word, lead: next, step: PointerStep(tokenId: "t1", gap: false), reduceMotion: true, gap: 2)
        _ = motion.frame(inputs, dt: 0, playing: true)
        inputs.box = next
        inputs.lead = nil
        inputs.step = PointerStep(tokenId: "t2", gap: false)
        XCTAssertTrue(motion.frame(inputs, dt: 16, playing: true))
        XCTAssertGreaterThan(motion.x, next.minX)
        XCTAssertLessThan(motion.x, next.maxX)
        XCTAssertEqual(motion.y, next.maxY + 2, accuracy: 0.01)
    }

    func testSpringSettlesOnTheNextSentenceWhilePaused() {
        let motion = HandMotion(initialSpeed: 0.14)
        var inputs = HandMotion.Inputs(box: CGRect(x: 100, y: 200, width: 60, height: 20), lead: nil, step: PointerStep(tokenId: "t1", gap: false), reduceMotion: false, gap: 2)
        _ = motion.frame(inputs, dt: 0, playing: true)
        // Pausa entre frases: la mano va al comienzo de la primera palabra de la frase siguiente, otra línea.
        inputs.box = CGRect(x: 40, y: 400, width: 50, height: 20)
        inputs.step = PointerStep(tokenId: "t9", gap: true)
        var settled = false
        for _ in 0..<180 where !settled {
            settled = motion.frame(inputs, dt: 16, playing: false)
        }
        XCTAssertTrue(settled, "the hand should come to rest within three seconds")
        XCTAssertEqual(motion.x, 46, accuracy: 0.5)
        XCTAssertEqual(motion.y, 422, accuracy: 0.5)
    }
}
