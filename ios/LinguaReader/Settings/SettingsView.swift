import SwiftUI
import LinguaShared

/// Índice de ajustes, como en Android: Contenido (Idiomas) y App (Lectura y apariencia, Acerca de).
struct SettingsView: View {
    var body: some View {
        List {
            Section("Content") {
                NavigationLink { LanguagesView() } label: { Label("Languages", systemImage: "globe") }
                    .accessibilityIdentifier("settings.languages")
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
                Link(destination: AppLinks.website) { LinkRow("Website", AppLinks.website.absoluteString, icon: "globe") }
                Link(destination: AppLinks.help) { LinkRow("Help", AppLinks.help.absoluteString, icon: "questionmark.circle") }
                Link(destination: AppLinks.contact(subject: "LingoAudioBook \(shortVersion): feedback")) {
                    LinkRow("Contact and suggestions", AppLinks.contactEmail, icon: "envelope")
                }
            } footer: {
                Text("No account, no ads and no tracking by the app. Your books, progress and vocabulary stay on your phone. The app only connects to the internet to download what you ask for (libraries, translators).")
            }.themedRows()
            Section("Open source licenses") {
                Text("This app uses the following components. Tap one to read its license.").font(.footnote).foregroundStyle(.secondary)
                ForEach(ThirdPartyNotices.included) { NoticeRow(notice: $0) }.themedRows()
            }.themedRows()
            if !ThirdPartyNotices.downloadable.isEmpty {
                Section("Optional downloads") {
                    ForEach(ThirdPartyNotices.downloadable) { NoticeRow(notice: $0) }.themedRows()
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

/// Texto de los motivos tipados de fallo de las descargas (`PackError`), traducible.
enum PackErrors {
    static func text(_ error: PackError) -> String {
        switch error {
        case let e as PackErrorNoSpace: return String(localized: "Not enough space: \(Int(e.neededMb)) MB needed, \(Int(e.freeMb)) MB free.")
        case let e as PackErrorDownloadInterruptedResumable: return String(localized: "Download interrupted (\(e.detail)). It will resume where it left off.")
        case let e as PackErrorDownloadInterrupted: return String(localized: "Download interrupted: \(e.detail)")
        case let e as PackErrorDownloadError: return String(localized: "Download failed: \(e.detail)")
        case let e as PackErrorLibraryFailed: return String(localized: "The library could not be installed: \(e.detail)")
        case let e as PackErrorFilesCorrupt: return String(localized: "Corrupt files: \(e.files)")
        case let e as PackErrorVerifyError: return String(localized: "Verification failed: \(e.detail)")
        case let e as PackErrorFolderInaccessible: return String(localized: "The model folder is not accessible: \(e.detail)")
        case is PackErrorFolderCreateFailed: return String(localized: "The model folder could not be created.")
        case is PackErrorRepoNotConfigured: return String(localized: "The download server is not configured.")
        case is PackErrorVoicePackCatalogUnavailable: return String(localized: "The voice pack catalog is unavailable.")
        default: return String(localized: "Something went wrong.")
        }
    }
}
