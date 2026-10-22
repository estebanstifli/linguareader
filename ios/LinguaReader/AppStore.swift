import Foundation
import SwiftUI
import LinguaShared

/// Arranca el código compartido (Koin, base de datos, preferencias, bibliotecas) y expone su estado a SwiftUI.
/// Todo lo que cambia (catálogo, progreso, estanterías, preferencias, descargas) llega por `FlowWatcher`.
@MainActor
final class AppStore: ObservableObject {
    @Published var books: [BookSummary] = []
    @Published var progress: [String: ReadingProgressEntity] = [:]
    @Published var shelves: LibraryState?
    @Published var prefs: UserPreferences?
    @Published var libraryCatalog: [String: LibraryInfo] = [:]
    @Published var libraryStates: [String: LibraryPackState] = [:]
    @Published var vocabulary: [VocabularyEntity] = []
    @Published var status: String = ""

    private var watchers: [FlowWatcher] = []
    private var covers: [String: UIImage] = [:]

    /// Argumentos de lanzamiento de los tests de UI: `-skipOnboarding`, `-importSample`.
    private let arguments = Set(ProcessInfo.processInfo.arguments)

    init() {
        let support = Self.applicationSupportDirectory()
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("LinguaReader", isDirectory: true)
        let catalog = Bundle.main.url(forResource: "libraries", withExtension: "json").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        LinguaShared.shared.start(appSupportDir: support.path, cacheDir: caches.path, librariesCatalogJson: catalog,
                                  packsRepo: "estebanstifli/linguareader", packsTag: "packs-v1", libraryTag: "library-v1")
        let shared = LinguaShared.shared
        watchers = [
            shared.watchCatalog { [weak self] list in self?.books = list },
            shared.watchProgress { [weak self] list in self?.progress = Dictionary(uniqueKeysWithValues: list.map { ($0.bookId, $0) }) },
            shared.watchShelves { [weak self] state in self?.shelves = state },
            shared.watchPreferences { [weak self] p in self?.prefs = p },
            shared.watchLibraryCatalog { [weak self] c in self?.libraryCatalog = c },
            shared.watchLibraryStates { [weak self] s in self?.libraryStates = s },
            shared.watchVocabulary { [weak self] v in self?.vocabulary = v },
        ]
        Task {
            if arguments.contains("-skipOnboarding") { try? await shared.skipOnboarding() }
            if arguments.contains("-importSample") { await importSampleIfEmpty() }
        }
    }

    // MARK: Primera apertura

    var needsOnboarding: Bool {
        guard let prefs else { return false }
        return prefs.purpose == nil || !prefs.onboardingSummaryShown
    }

    // MARK: Biblioteca

    func downloadLibrary(_ lang: String) { LinguaShared.shared.libraryPacks.download(lang: lang) }

    func deleteLibrary(_ lang: String) { Task { try? await LinguaShared.shared.libraryPacks.delete(lang: lang) } }

    func isLibraryBook(_ bookId: String) -> Bool { LinguaShared.shared.isLibraryBook(bookId: bookId) }

    /// Fracción leída (0–1) o nil si el libro no se ha abierto.
    func progressFraction(_ bookId: String) -> Double? {
        guard let p = progress[bookId], p.totalPassages > 0 else { return nil }
        return min(1, Double(p.passageIndex) / Double(p.totalPassages))
    }

    func setInShelf(_ shelfId: Int64, _ bookId: String, _ inShelf: Bool) {
        Task { try? await LinguaShared.shared.library.setInShelf(shelfId: shelfId, bookId: bookId, inShelf: inShelf) }
    }

    func createCollection(_ name: String, bookId: String?) {
        Task { _ = try? await LinguaShared.shared.library.createCollection(name: name, bookId: bookId) }
    }

    func deleteCollection(_ id: Int64) { Task { try? await LinguaShared.shared.library.deleteCollection(id: id) } }

    func trash(_ book: BookSummary) { Task { try? await LinguaShared.shared.library.trash(bookId: book.id, title: book.title) } }

    func restore(_ bookId: String) { Task { try? await LinguaShared.shared.library.restore(bookId: bookId) } }

    func canDeleteForever(_ bookId: String) -> Bool { LinguaShared.shared.library.canDeleteForever(bookId: bookId) }

    func deleteForever(_ bookId: String) { Task { try? await LinguaShared.shared.library.deleteForever(bookId: bookId) } }

    func setLibraryGrid(_ grid: Bool) { Task { try? await LinguaShared.shared.setLibraryGrid(grid: grid) } }

    func deleteVocabulary(_ id: Int64) { Task { try? await LinguaShared.shared.deleteVocabulary(id: id) } }

    /// Portada reducida, con caché en memoria; nil si el libro no la tiene (se dibuja una generada).
    func cover(for book: BookSummary) async -> UIImage? {
        if let cached = covers[book.id] { return cached }
        guard let ref = book.cover,
              let data = try? await LinguaShared.shared.loadCover(bookId: book.id, cover: ref),
              let image = UIImage(data: data)?.preparingThumbnail(of: CGSize(width: 240, height: 360)) else { return nil }
        covers[book.id] = image
        return image
    }

    // MARK: Muestra (tests de UI)

    func importSampleIfEmpty() async {
        guard (try? await LinguaShared.shared.isLibraryEmpty())?.boolValue == true,
              let url = Bundle.main.url(forResource: "cinderella", withExtension: "epub") else { return }
        status = "Importing the sample book…"
        do {
            _ = try await LinguaShared.shared.importFile(path: url.path, displayName: "cinderella.epub", language: "en", level: nil)
            status = ""
        } catch {
            status = "Import failed: \(error.localizedDescription)"
        }
    }

    /// Carpeta persistente de la app, excluida de la copia de iCloud (modelos y caché no deben subir).
    private static func applicationSupportDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = base.appendingPathComponent("LinguaReader", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        return dir
    }
}

/// Nombres legibles de los idiomas que maneja la app.
enum Languages {
    static let names: [String: String] = ["en": "English", "es": "Spanish", "fr": "French", "de": "German", "it": "Italian", "pt": "Portuguese", "nl": "Dutch", "ca": "Catalan"]
    static let translationTargets = ["en", "es", "fr", "de", "it", "pt"]

    static func name(_ tag: String) -> String {
        let primary = tag.split(separator: "-").first.map(String.init)?.lowercased() ?? tag
        return names[primary] ?? Locale.current.localizedString(forLanguageCode: primary) ?? tag
    }

    static func primary(_ tag: String) -> String { tag.split(separator: "-").first.map(String.init)?.lowercased() ?? tag }
}
