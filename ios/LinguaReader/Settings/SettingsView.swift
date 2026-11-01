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
            }.themedRows()
            Section("App") {
                NavigationLink { ReadingSettingsView() } label: { Label("Reading and appearance", systemImage: "textformat.size") }
                    .accessibilityIdentifier("settings.reading")
                NavigationLink { AboutView() } label: { Label("About", systemImage: "info.circle") }
            }.themedRows()
        }
        .themedScreen()
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
            }.themedRows()
            ForEach(languages, id: \.self) { lang in
                if let info = store.libraryCatalog[lang] {
                    LibraryRow(lang: lang, info: info, state: store.libraryStates[lang])
                }
            }
        }
        .themedScreen()
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

/// Ajustes › Acerca de: versión, privacidad, contacto, web y avisos de terceros (licencias), como en Android.
struct AboutView: View {
    private var shortVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "" }
    private var build: String { Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "" }

    var body: some View {
        List {
            Section {
                Text("LingoAudioBook").font(.headline)
                Text("Version \(shortVersion) (\(build)) · shared code \(LinguaShared.shared.VERSION)")
                    .font(.footnote).foregroundStyle(.secondary)
                Text("Learn languages by reading and listening: graded readers, a voice that highlights each word, and translation on tap, all offline.")
                    .font(.footnote)
            }.themedRows()
            Section {
                Link(destination: AppLinks.privacyPolicy) { LinkRow("Privacy policy", AppLinks.privacyPolicy.absoluteString, icon: "hand.raised") }
                Link(destination: AppLinks.website) { LinkRow("Website and help", AppLinks.website.absoluteString, icon: "globe") }
                Link(destination: AppLinks.contact(subject: "LingoAudioBook \(shortVersion): feedback")) {
                    LinkRow("Contact and suggestions", AppLinks.contactEmail, icon: "envelope")
                }
            } footer: {
                Text("No account, no ads and no tracking by the app. Your books, progress and vocabulary stay on your phone. The app only connects to the internet to download what you ask for (libraries, translators).")
            }.themedRows()
            Section("Open source licenses") {
                Text("This app uses the following components. Tap one to read its license.").font(.footnote).foregroundStyle(.secondary)
                ForEach(ThirdPartyNotices.included) { NoticeRow(notice: $0) }
            }.themedRows()
            if !ThirdPartyNotices.downloadable.isEmpty {
                Section("Optional downloads") {
                    ForEach(ThirdPartyNotices.downloadable) { NoticeRow(notice: $0) }
                }.themedRows()
            }
            Section {
                Text("Graded readers: our own adaptations of public-domain works. Your imported books stay only on your device.")
                    .font(.footnote).foregroundStyle(.secondary)
            }.themedRows()
            Section("Troubleshooting") {
                NavigationLink { DiagnosticsView() } label: { Label("Diagnostics", systemImage: "stethoscope") }
            }.themedRows()
        }
        .themedScreen()
        .navigationTitle("About")
    }
}

private struct LinkRow: View {
    let title: LocalizedStringKey
    let detail: String
    let icon: String

    init(_ title: LocalizedStringKey, _ detail: String, icon: String) {
        self.title = title
        self.detail = detail
        self.icon = icon
    }

    var body: some View {
        Label {
            VStack(alignment: .leading) {
                Text(title).foregroundStyle(.primary)
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }
        } icon: { Image(systemName: icon) }
    }
}

private struct NoticeRow: View {
    let notice: ThirdPartyNotice

    var body: some View {
        Link(destination: notice.url) {
            VStack(alignment: .leading) {
                Text(notice.name).foregroundStyle(.primary)
                Text("\(notice.author) · \(notice.license)").font(.footnote).foregroundStyle(.secondary)
            }
        }
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
