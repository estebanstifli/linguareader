import Foundation
import LinguaShared

/// Arranca el código compartido (Koin, base de datos, preferencias) y expone el catálogo a SwiftUI.
/// En la primera apertura importa el libro de muestra incluido en el bundle con el importador compartido.
@MainActor
final class AppStore: ObservableObject {
    @Published var books: [BookSummary] = []
    @Published var status: String = ""

    private var catalogWatcher: FlowWatcher?

    init() {
        let support = Self.applicationSupportDirectory()
        LinguaShared.shared.start(appSupportDir: support.path)
        catalogWatcher = LinguaShared.shared.watchCatalog { [weak self] list in
            self?.books = list
        }
        Task { await importSampleIfEmpty() }
    }

    func importSampleIfEmpty() async {
        guard LinguaShared.shared.isLibraryEmpty(),
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
