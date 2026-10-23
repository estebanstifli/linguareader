import SwiftUI
import LinguaShared

/// Índice de ajustes. En este hito: Bibliotecas y Acerca de; el resto llega con el lector completo y la traducción.
struct SettingsView: View {
    var body: some View {
        List {
            Section("Content") {
                NavigationLink { LibrariesView() } label: {
                    Label { Text("Libraries"); } icon: { Image(systemName: "books.vertical") }
                }
                .accessibilityIdentifier("settings.libraries")
                NavigationLink { TranslationView() } label: { Label("Translation", systemImage: "character.bubble") }
                    .accessibilityIdentifier("settings.translation")
            }
            Section("App") {
                NavigationLink { AboutView() } label: { Label("About", systemImage: "info.circle") }
            }
        }
        .navigationTitle("Settings")
    }
}

/// Bibliotecas de libros graduados por idioma: descargar, actualizar y quitar (misma lógica que Android, módulo :domain).
struct LibrariesView: View {
    @EnvironmentObject private var store: AppStore

    private var languages: [String] { store.libraryCatalog.keys.sorted() }

    var body: some View {
        List {
            Section {
                Text("Graded readers (A1–B2) with word-by-word audio. Each library is about 5 MB and works offline once downloaded.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(languages, id: \.self) { lang in
                if let info = store.libraryCatalog[lang] {
                    LibraryRow(lang: lang, info: info, state: store.libraryStates[lang])
                }
            }
        }
        .navigationTitle("Libraries")
    }
}

struct LibraryRow: View {
    @EnvironmentObject private var store: AppStore
    let lang: String
    let info: LibraryInfo
    let state: LibraryPackState?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Languages.name(lang)).font(.headline)
            Text("\(info.books) books · \(ByteCountFormatter.string(fromByteCount: info.sizeBytes, countStyle: .file))")
                .font(.subheadline).foregroundStyle(.secondary)
            switch state {
            case let s as LibraryPackStateDownloading:
                ProgressView(value: s.total > 0 ? Double(s.bytes) / Double(s.total) : 0) { Text("Downloading…") }
                    .accessibilityIdentifier("library.downloading.\(lang)")
            case let s as LibraryPackStateInstalling:
                ProgressView(value: s.total > 0 ? Double(s.done) / Double(s.total) : 0) { Text("Installing \(s.done)/\(s.total)…") }
                    .accessibilityIdentifier("library.installing.\(lang)")
            case let s as LibraryPackStateInstalled:
                HStack {
                    Label("Installed · \(s.books) books", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                        .accessibilityIdentifier("library.installed.\(lang)")
                    Spacer()
                    if s.updateAvailable {
                        Button("Update") { store.downloadLibrary(lang) }.buttonStyle(.borderedProminent)
                    }
                    Button("Remove", role: .destructive) { store.deleteLibrary(lang) }.buttonStyle(.bordered)
                }
            case let s as LibraryPackStateFailed:
                Text(PackErrors.text(s.error)).font(.footnote).foregroundStyle(.red)
                Button("Retry") { store.downloadLibrary(lang) }.buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("library.retry.\(lang)")
            default:
                Button("Download") { store.downloadLibrary(lang) }.buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("library.download.\(lang)")
            }
        }
        .padding(.vertical, 4)
    }
}

struct AboutView: View {
    var body: some View {
        List {
            Section {
                Text("LinguaReader for iOS").font(.headline)
                Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") · shared code \(LinguaShared.shared.VERSION)")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Licenses") {
                Text("Graded readers: adapted public-domain texts, see each book's information. Shared engine: Kotlin Multiplatform, Okio, Room, Koin (Apache-2.0).")
                    .font(.footnote)
            }
            Section("Troubleshooting") {
                NavigationLink { DiagnosticsView() } label: { Label("Diagnostics", systemImage: "stethoscope") }
            }
        }
        .navigationTitle("About")
    }
}

/// Texto de los motivos tipados de fallo (hasta que llegue el catálogo de cadenas en el hito 3).
enum PackErrors {
    static func text(_ error: PackError) -> String {
        switch error {
        case let e as PackErrorNoSpace: return "Not enough space: \(e.neededMb) MB needed, \(e.freeMb) MB free."
        case let e as PackErrorDownloadInterruptedResumable: return "Download interrupted (\(e.detail)). It will resume where it left off."
        case let e as PackErrorDownloadInterrupted: return "Download interrupted: \(e.detail)"
        case let e as PackErrorDownloadError: return "Download failed: \(e.detail)"
        case let e as PackErrorLibraryFailed: return "The library could not be installed: \(e.detail)"
        case let e as PackErrorFilesCorrupt: return "Corrupt files: \(e.files)"
        case let e as PackErrorVerifyError: return "Verification failed: \(e.detail)"
        case let e as PackErrorFolderInaccessible: return "The model folder is not accessible: \(e.detail)"
        case is PackErrorFolderCreateFailed: return "The model folder could not be created."
        case is PackErrorRepoNotConfigured: return "The download server is not configured."
        case is PackErrorVoicePackCatalogUnavailable: return "The voice pack catalog is unavailable."
        default: return "Something went wrong."
        }
    }
}
