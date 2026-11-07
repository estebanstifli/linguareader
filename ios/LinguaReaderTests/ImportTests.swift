import XCTest
import LinguaShared
@testable import LinguaReader

/// Importación de documentos del usuario por la fachada compartida (fase 3): TXT, HTML y FB2 de `Fixtures/`, texto
/// pegado y un fichero que no se puede importar. Corre en el simulador (CI) y en el iPhone real (Device Farm).
final class ImportTests: XCTestCase {

    private func fixture(_ name: String) throws -> URL {
        let bundle = Bundle(for: ImportTests.self)
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        guard let url = bundle.url(forResource: base, withExtension: ext) ?? bundle.url(forResource: name, withExtension: nil, subdirectory: "Fixtures") else {
            throw XCTSkip("Fixture \(name) not in the test bundle")
        }
        return url
    }

    private func draft(_ name: String) async throws -> ImportDraft {
        let url = try fixture(name)
        let outcome = try await LinguaShared.shared.readDocument(path: url.path, displayName: name, mimeType: nil)
        guard let draft = outcome as? ImportOutcomeDraft else {
            let failed = outcome as? ImportOutcomeFailed
            XCTFail("Expected a draft for \(name), got \(String(describing: failed?.reason)) \(failed?.detail ?? "")")
            throw XCTSkip("no draft")
        }
        return draft.draft
    }

    func testPlainTextBecomesChaptersAndParagraphs() async throws {
        let d = try await draft("sample.txt")
        XCTAssertEqual(d.document.format, "txt")
        XCTAssertGreaterThanOrEqual(Int(d.document.paragraphCount), 4, "\(d.document.chapters.count) chapters")
        XCTAssertGreaterThanOrEqual(d.document.chapters.count, 2)
        XCTAssertEqual(d.sourceName, "sample.txt")
    }

    func testHtmlKeepsTitleLanguageAndMainContent() async throws {
        let d = try await draft("sample.html")
        XCTAssertEqual(d.document.format, "html")
        XCTAssertEqual(d.document.title, "Le petit jardin")
        XCTAssertEqual(d.detectedLanguage, "fr")
        XCTAssertGreaterThanOrEqual(Int(d.document.paragraphCount), 3)
    }

    func testFb2KeepsAuthorAndLanguageAndCommitsAsABook() async throws {
        let d = try await draft("sample.fb2")
        XCTAssertEqual(d.document.format, "fb2")
        XCTAssertEqual(d.document.title, "El jardín tranquilo")
        XCTAssertEqual(d.detectedLanguage, "es")
        XCTAssertEqual(d.document.authors.first, "Lucía Prueba")
        XCTAssertEqual(d.document.chapters.count, 2)

        let id = try await LinguaShared.shared.commitImport(draft: d, title: d.document.title ?? "", author: d.document.authors.joined(separator: ", "), language: "es", level: .a2, translateNow: false)
        XCTAssertTrue(id.hasPrefix(ImportedBookRepository.companion.ID_PREFIX))
        let book = try await LinguaShared.shared.books.loadBook(bookId: id)
        XCTAssertEqual(book.title, "El jardín tranquilo")
        XCTAssertEqual(book.cefrLevel, .a2)
        XCTAssertEqual(book.chapters.count, 2)
        XCTAssertTrue(LinguaShared.shared.isImportedBook(bookId: id))
        try await LinguaShared.shared.library.trash(bookId: id, title: book.title)
        try await LinguaShared.shared.library.deleteForever(bookId: id)
    }

    func testPastedTextUsesFirstLineAsTitle() async throws {
        let outcome = try await LinguaShared.shared.readText(text: "A walk in the park\n\nIt was a sunny day. The children were playing.", title: nil)
        guard let d = (outcome as? ImportOutcomeDraft)?.draft else { return XCTFail("\(outcome)") }
        XCTAssertEqual(d.sourceName, "A walk in the park")
        XCTAssertGreaterThanOrEqual(Int(d.document.paragraphCount), 1)
    }

    func testUnsupportedFileIsReportedWithAReason() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("noise.bin")
        try Data([0x00, 0x01, 0x02, 0x03, 0xFF, 0xFE, 0x00, 0x10]).write(to: url)
        let outcome = try await LinguaShared.shared.readDocument(path: url.path, displayName: "noise.bin", mimeType: nil)
        guard let failed = outcome as? ImportOutcomeFailed else { return XCTFail("A binary blob should not import") }
        XCTAssertNotNil(failed.reason)
    }

    func testTextPageCoverIsDrawn() {
        let image = CoverThumbnails.drawTextPage(title: "El jardín tranquilo", body: "Ana abrió la verja y entró en el jardín. La hierba estaba mojada.")
        XCTAssertEqual(image.size.width, 240)
        XCTAssertEqual(image.size.height, 360)
    }
}
