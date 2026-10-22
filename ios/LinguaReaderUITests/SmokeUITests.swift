import XCTest

/// Humo de la app en simulador (CI) y en iPhone real (Device Farm, XCTest UI).
final class SmokeUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Libro de muestra importado con el importador compartido, lectura con la voz del sistema, cambio de página.
    func testOpensTheSampleBookAndStartsReading() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-skipOnboarding", "-importSample"]
        app.launch()

        let row = app.descendants(matching: .any).matching(identifier: "catalog.row").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 90), "The sample book should appear after the first import")
        row.tap()

        let text = app.staticTexts["reader.text"]
        XCTAssertTrue(text.waitForExistence(timeout: 30), "The first passage should be shown")
        XCTAssertFalse(text.label.isEmpty)

        let play = app.buttons["reader.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 10))
        play.tap()

        let status = app.staticTexts["reader.status"]
        let playing = NSPredicate(format: "label CONTAINS[c] 'playing'")
        expectation(for: playing, evaluatedWith: status)
        waitForExpectations(timeout: 30)

        app.buttons["reader.next"].tap()
        XCTAssertTrue(text.waitForExistence(timeout: 10))
    }

    /// Primera apertura completa: aprender inglés, nivel A1, descarga e instalación de la biblioteca (38 libros) y
    /// catálogo «Learn English» con libros. Necesita red (GitHub releases).
    func testOnboardingDownloadsTheEnglishLibrary() throws {
        let app = XCUIApplication()
        app.launch()

        let learn = app.descendants(matching: .any).matching(identifier: "onboarding.learn").firstMatch
        XCTAssertTrue(learn.waitForExistence(timeout: 30))
        learn.tap()

        let english = app.descendants(matching: .any).matching(identifier: "onboarding.language.en").firstMatch
        XCTAssertTrue(english.waitForExistence(timeout: 30), "The bundled libraries catalog should list English")
        english.tap()

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
        XCTAssertGreaterThan(app.descendants(matching: .any).matching(identifier: "catalog.row").count, 5)
    }
}
