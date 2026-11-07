import XCTest

/// Humo de la app en simulador (CI) y en iPhone real (Device Farm, XCTest UI).
final class SmokeUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
        // Si la app del test anterior sigue abierta, se cierra antes (launch() falla si no logra terminarla).
        let app = XCUIApplication()
        if app.state != .notRunning { app.terminate() }
    }

    /// Libro de muestra importado con el importador compartido, lectura con la voz del sistema, cambio de página.
    func test1OpensTheSampleBookAndStartsReading() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-skipOnboarding", "-importSample"]
        app.launch()

        let row = app.descendants(matching: .any).matching(identifier: "catalog.row").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 90), "The sample book should appear after the first import")
        row.tap()

        // La fila abre la ficha del libro; «Read» abre el lector (sin arrancar la voz: la arranca el test).
        let read = app.buttons["detail.read"]
        XCTAssertTrue(read.waitForExistence(timeout: 10), "The book detail should offer Read")
        read.tap()

        let text = app.descendants(matching: .any).matching(identifier: "reader.text").firstMatch
        XCTAssertTrue(text.waitForExistence(timeout: 30), "The first passage should be shown")

        let play = app.buttons["reader.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 10))
        play.tap()

        let status = app.staticTexts["reader.status"]
        let playing = NSPredicate(format: "label CONTAINS[c] 'playing'")
        expectation(for: playing, evaluatedWith: status)
        waitForExpectations(timeout: 30)

        app.buttons["reader.next"].tap()
        XCTAssertTrue(text.waitForExistence(timeout: 10))

        // Pulsación larga sobre el texto: hoja de la frase con «Listen to sentence» (fase 2).
        app.buttons["reader.pause"].tap()
        text.press(forDuration: 0.8)
        let sentence = app.descendants(matching: .any).matching(identifier: "sentence.sheet").firstMatch
        XCTAssertTrue(sentence.waitForExistence(timeout: 15), "A long press should open the sentence sheet")
        XCTAssertTrue(app.buttons["sentence.listen"].waitForExistence(timeout: 5))
        app.buttons["Close"].firstMatch.tap()

        // Toque en una palabra: el popup compacto, y «More information» abre la ficha completa.
        text.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap()
        let popup = app.descendants(matching: .any).matching(identifier: "word.popup").firstMatch
        if popup.waitForExistence(timeout: 10) {
            app.buttons["word.moreInfo"].tap()
            XCTAssertTrue(app.navigationBars["Word"].waitForExistence(timeout: 10), "The full word card should open from the popup")
        }
    }

    /// Primera apertura completa: aprender inglés, nivel A1, descarga e instalación de la biblioteca (38 libros) y
    /// catálogo «Learn English» con libros. Necesita red (GitHub releases).
    func test2OnboardingDownloadsTheEnglishLibrary() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-resetOnboarding"]
        app.launch()

        // Sin paso de propósito: la app de iPhone es solo para aprender idiomas.
        let english = app.descendants(matching: .any).matching(identifier: "onboarding.language.en").firstMatch
        XCTAssertTrue(english.waitForExistence(timeout: 30), "The bundled libraries catalog should list English")
        english.tap()

        // El simulador está en inglés: «tu idioma» sería el mismo que el que se aprende (Continue deshabilitado), así
        // que se elige español en el selector, como haría un usuario.
        let native = app.descendants(matching: .any).matching(identifier: "onboarding.native").firstMatch
        XCTAssertTrue(native.waitForExistence(timeout: 10))
        native.tap()
        let spanish = app.buttons["Spanish"]
        XCTAssertTrue(spanish.waitForExistence(timeout: 10), "The native-language menu should list Spanish")
        spanish.tap()

        let cont = app.buttons["onboarding.continue"]
        XCTAssertTrue(cont.waitForExistence(timeout: 10))
        cont.tap()

        let a1 = app.descendants(matching: .any).matching(identifier: "onboarding.level.A1").firstMatch
        XCTAssertTrue(a1.waitForExistence(timeout: 10))
        a1.tap()
        app.buttons["onboarding.download"].tap()

        let start = app.buttons["onboarding.start"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        start.tap()

        // Catálogo: la sección «Learn English» se llena cuando la biblioteca queda instalada.
        let row = app.descendants(matching: .any).matching(identifier: "catalog.row").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 300), "Library books should appear once the download finishes")
        // La lista es perezosa: solo existen las celdas visibles.
        XCTAssertGreaterThanOrEqual(app.descendants(matching: .any).matching(identifier: "catalog.row").count, 3)
    }
}
