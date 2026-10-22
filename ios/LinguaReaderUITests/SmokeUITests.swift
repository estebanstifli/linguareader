import XCTest

/// Humo de la app en simulador (CI) y en iPhone real (Device Farm, XCTest UI): arranca, importa la muestra,
/// abre el libro y empieza a leer con la voz del sistema.
final class SmokeUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testOpensTheSampleBookAndStartsReading() throws {
        let app = XCUIApplication()
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
}
