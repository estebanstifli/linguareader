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
    @Published var translationModels: TranslationModelsState?
    @Published var bookTranslations: [String: BookTranslationState] = [:]
    @Published var translationStatus: [String: ImportedBookRepository.TranslationStatus] = [:]
    @Published var narrationDownloading: Set<String> = []
    /// Último libro cuya narración quedó descargada (la ficha se refresca con él).
    @Published var narrationReady: String?
    @Published var status: String = ""
    /// Fichero que otra app ha pedido abrir con LingoAudioBook («Abrir en…»); el catálogo lo importa y lo vacía.
    @Published var openedDocument: URL?

    /// Traductor ML Kit (solo en la app de iPhone: los pods no tienen rebanada arm64 de simulador, ver ios/Podfile);
    /// vive lo que la app porque ML Kit avisa de las descargas por notificaciones.
    private let translator: IosTranslator? = {
        #if canImport(MLKitTranslate)
        return MlKitTranslator()
        #else
        return nil
        #endif
    }()

    private var watchers: [FlowWatcher] = []

    /// Argumentos de lanzamiento de los tests de UI: `-skipOnboarding`, `-resetOnboarding`, `-importSample`.
    private let arguments = Set(ProcessInfo.processInfo.arguments)

    init() {
        let support = Self.applicationSupportDirectory()
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("LinguaReader", isDirectory: true)
        let catalog = Bundle.main.url(forResource: "libraries", withExtension: "json").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        LinguaShared.shared.start(appSupportDir: support.path, cacheDir: caches.path, librariesCatalogJson: catalog,
                                  packsBaseUrl: "https://files.lingoaudiobook.eu", packsTag: "packs-v1", libraryTag: "library-v1",
                                  translator: translator)
        let shared = LinguaShared.shared
        watchers = [
            shared.watchCatalog { [weak self] list in self?.books = list },
            shared.watchProgress { [weak self] list in self?.progress = Dictionary(uniqueKeysWithValues: list.map { ($0.bookId, $0) }) },
            shared.watchShelves { [weak self] state in self?.shelves = state },
            shared.watchPreferences { [weak self] p in self?.prefs = p },
            shared.watchLibraryCatalog { [weak self] c in self?.libraryCatalog = c },
            shared.watchLibraryStates { [weak self] s in self?.libraryStates = s },
            shared.watchVocabulary { [weak self] v in self?.vocabulary = v },
            shared.watchBookTranslations { [weak self] m in self?.bookTranslations = m },
            shared.watchTranslationStatus { [weak self] m in self?.translationStatus = m },
            shared.watchNarrationDownloads { [weak self] s in self?.narrationDownloading = s },
            shared.watchNarrationReady { [weak self] id in self?.narrationReady = id },
        ]
        if let models = shared.watchTranslationModels(block: { [weak self] s in self?.translationModels = s }) { watchers.append(models) }
        Task {
            if arguments.contains("-resetOnboarding") { try? await shared.resetOnboarding() }
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

    /// Libro importado por el usuario o instalado desde una biblioteca (se puede pretraducir).
    func isImported(_ bookId: String) -> Bool { LinguaShared.shared.isImportedBook(bookId: bookId) }

    // MARK: Idiomas

    /// Idiomas que se aprenden (subetiqueta primaria), en el orden elegido.
    var learningLanguages: [String] { (prefs?.learningLanguages ?? []).map(Languages.primary) }

    /// Idiomas a los que se puede traducir: los del traductor del dispositivo; sin traductor (simulador), los de la interfaz.
    var nativeOptions: [String] { translationModels?.supported ?? Languages.fallbackTargets }

    /// Idiomas que se pueden aprender: los que tienen biblioteca primero, después los que la voz sabe leer, por nombre.
    func learnable() -> [String] {
        let catalog = Set(libraryCatalog.keys)
        let all = catalog.union(LinguaShared.shared.voiceLanguages())
        return all.sorted { a, b in
            let inCatalog = (catalog.contains(a), catalog.contains(b))
            if inCatalog.0 != inCatalog.1 { return inCatalog.0 }
            return Languages.name(a).localizedCaseInsensitiveCompare(Languages.name(b)) == .orderedAscending
        }
    }

    /// Códigos ML Kit entre los que se traduce al aprender `lang`: el suyo y el nativo (nada si son el mismo o no se traducen).
    func neededModels(_ lang: String, native: String) -> [String] {
        var codes: [String] = []
        for tag in [lang, native] {
            if let code = LinguaShared.shared.translationCode(languageTag: tag), !codes.contains(code) { codes.append(code) }
        }
        return codes.count > 1 ? codes : []
    }

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

    func deleteForever(_ bookId: String) {
        Task {
            try? await LinguaShared.shared.library.deleteForever(bookId: bookId)
            await CoverThumbnails.shared.forget(bookId)
        }
    }

    func setLibraryGrid(_ grid: Bool) { Task { try? await LinguaShared.shared.setLibraryGrid(grid: grid) } }

    /// «Share as text» del menú de un libro: el texto plano en un .txt temporal por la hoja de compartir del sistema.
    func shareText(of book: BookSummary) {
        Task {
            guard let text = try? await LinguaShared.shared.bookText(bookId: book.id) else { return }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(book.title.replacingOccurrences(of: "/", with: "-")).txt")
            try? text.write(to: url, atomically: true, encoding: .utf8)
            let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController?.present(controller, animated: true)
        }
    }

    func deleteVocabulary(_ id: Int64) { Task { try? await LinguaShared.shared.deleteVocabulary(id: id) } }

    /// Portada reducida (caché en disco y memoria): la del libro o, para los documentos importados sin portada, una
    /// página de texto dibujada; nil para los pocos casos restantes (se dibuja un degradado con el título).
    func cover(for book: BookSummary) async -> UIImage? {
        await CoverThumbnails.shared.load(book, textPage: !isLibraryBook(book.id))
    }

    // MARK: Muestra (tests de UI)

    func importSampleIfEmpty() async {
        guard (try? await LinguaShared.shared.isLibraryEmpty())?.boolValue == true,
              let url = Bundle.main.url(forResource: "cinderella", withExtension: "epub") else { return }
        status = String(localized: "Importing the sample book…")
        do {
            _ = try await LinguaShared.shared.importFile(path: url.path, displayName: "cinderella.epub", language: "en", level: nil)
            status = ""
        } catch {
            status = String(localized: "Import failed: \(error.localizedDescription)")
        }
    }

    /// Carpeta persistente de la app. Como `backup_rules.xml` en Android, a la copia de iCloud van solo la base de datos
    /// y las preferencias (progreso, marcadores, vocabulario, estanterías); los libros descargados o importados, las
    /// bibliotecas, las narraciones, los modelos y la caché de audio se excluyen (se vuelven a descargar o importar).
    private static func applicationSupportDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = base.appendingPathComponent("LinguaReader", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var include = URLResourceValues()
        include.isExcludedFromBackup = false
        try? dir.setResourceValues(include)
        for name in ["imported", "libraries", "narrations", "models", "audio-cache", "import"] {
            var sub = dir.appendingPathComponent(name, isDirectory: true)
            try? FileManager.default.createDirectory(at: sub, withIntermediateDirectories: true)
            var exclude = URLResourceValues()
            exclude.isExcludedFromBackup = true
            try? sub.setResourceValues(exclude)
        }
        return dir
    }
}

/// Nombres de los idiomas en el idioma de la interfaz (los da el sistema, para cualquier etiqueta BCP-47).
enum Languages {
    /// Idiomas de la interfaz; a los que se traduce cuando la app no tiene traductor (simulador).
    static let fallbackTargets = ["en", "es", "fr", "de", "it", "pt"]

    /// Nombre con mayúscula inicial, para títulos y listas («Inglés»).
    static func name(_ tag: String) -> String {
        let n = nameInSentence(tag)
        return n.prefix(1).uppercased() + n.dropFirst()
    }

    /// Nombre tal como va dentro de una frase («traducir al inglés»): como lo da el sistema en cada idioma.
    static func nameInSentence(_ tag: String) -> String {
        let code = primary(tag)
        return Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    static func primary(_ tag: String) -> String { tag.split(separator: "-").first.map(String.init)?.lowercased() ?? tag }
}

/// Las clases de Kotlin llegan como clases ObjC; `id` ya es un String único.
extension BookSummary: Identifiable {}
