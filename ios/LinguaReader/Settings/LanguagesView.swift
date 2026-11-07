import SwiftUI
import LinguaShared

/// Ajustes › Languages y «Learn other languages» del catálogo: tu idioma (con su traductor), los idiomas que aprendes
/// (biblioteca, traductor, quitar) y los que puedes añadir. Misma lógica que `LanguagesSection` en Android; cada cambio
/// se aplica al momento (las descargas las lanza `LinguaShared.addLearningLanguage` / `setNativeLanguage`).
struct LanguagesView: View {
    @EnvironmentObject private var store: AppStore
    @State private var showAll = false

    var body: some View {
        List {
            if let p = store.prefs {
                let native = LinguaShared.shared.translationCode(languageTag: p.translationLanguage) ?? Languages.primary(p.translationLanguage)
                Section {
                    Picker("Your language", selection: Binding(get: { native }, set: { code in
                        Task { try? await LinguaShared.shared.setNativeLanguage(code: code) }
                    })) {
                        ForEach(store.nativeOptions, id: \.self) { Text(Languages.name($0)).tag($0) }
                    }
                    .accessibilityIdentifier("languages.native")
                    if store.translationModels != nil { TranslationModelRow(code: native, deletable: false) }
                    Toggle("Download translators only over Wi-Fi (≈40 MB per language)", isOn: Binding(get: { p.translationWifiOnly }, set: { v in
                        Task { try? await LinguaShared.shared.setTranslationWifiOnly(enabled: v) }
                    }))
                } footer: {
                    Text("Your native language: words and sentences are translated into it. Translation happens on the phone with Google ML Kit and works offline once each language's model is downloaded (≈40 MB).")
                }.themedRows()

                Section("Languages you're learning") {
                    if store.learningLanguages.isEmpty {
                        Text("You haven't chosen any language yet.").foregroundStyle(.secondary)
                    }
                    ForEach(store.learningLanguages, id: \.self) { lang in
                        LearningCard(lang: lang, native: native)
                    }
                }.themedRows()

                let others = store.learnable().filter { lang in !store.learningLanguages.contains(lang) }
                let shown = showAll ? others : others.filter { store.libraryCatalog[$0] != nil }
                if !others.isEmpty {
                    Section("Add a language") {
                        ForEach(shown, id: \.self) { lang in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(Languages.name(lang))
                                    Text(libraryAvailability(store.libraryCatalog[lang])).font(.footnote).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button("Add") { Task { try? await LinguaShared.shared.addLearningLanguage(tag: lang) } }
                                    .buttonStyle(.bordered)
                                    .disabled(lang == native)
                            }
                            .accessibilityIdentifier("languages.add.\(lang)")
                        }
                        if !showAll && shown.count < others.count {
                            Button("Show \(others.count - shown.count) more languages (voice and translation, books coming soon)") { showAll = true }
                                .font(.subheadline)
                        }
                    }.themedRows()
                }

                let extra = otherDownloadedModels(native: native)
                if !extra.isEmpty {
                    Section("Other downloaded languages") {
                        ForEach(extra, id: \.self) { TranslationModelRow(code: $0, deletable: true) }
                    }.themedRows()
                }
            }
        }
        .themedScreen()
        .navigationTitle("Languages")
    }

    /// Modelos descargados que ningún idioma que se aprende necesita (se pueden borrar para liberar espacio).
    private func otherDownloadedModels(native: String) -> [String] {
        guard let models = store.translationModels else { return [] }
        var needed = Set([native])
        for lang in store.learningLanguages { needed.formUnion(store.neededModels(lang, native: native)) }
        return models.downloaded.filter { !needed.contains($0) }.sorted()
    }
}

/// «16 books · 2.1 MB» si hay biblioteca; si no, «Books coming soon».
func libraryAvailability(_ info: LibraryInfo?) -> String {
    guard let info else { return String(localized: "Books coming soon · voice and translation available") }
    return "\(info.books) books · \(ByteCountFormatter.string(fromByteCount: info.sizeBytes, countStyle: .file))"
}

/// Un idioma que se aprende: biblioteca (estado y acciones), traductores que hacen falta y «Remove».
private struct LearningCard: View {
    @EnvironmentObject private var store: AppStore
    let lang: String
    let native: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(Languages.name(lang)).font(.headline)
                Spacer()
                Button("Remove") { Task { try? await LinguaShared.shared.removeLearningLanguage(tag: lang) } }
                    .buttonStyle(.borderless).font(.subheadline)
            }
            libraryRow
            ForEach(store.neededModels(lang, native: native).filter { $0 != native }, id: \.self) { code in
                TranslationModelRow(code: code, deletable: true)
            }
        }
        .padding(.vertical, 4)
        .accessibilityIdentifier("languages.learning.\(lang)")
    }

    @ViewBuilder
    private var libraryRow: some View {
        let info = store.libraryCatalog[lang]
        switch store.libraryStates[lang] {
        case let s as LibraryPackStateDownloading:
            ProgressView(value: s.total > 0 ? Double(s.bytes) / Double(s.total) : 0) { Text("Downloading the library…").font(.subheadline) }
        case let s as LibraryPackStateInstalling:
            ProgressView(value: s.total > 0 ? Double(s.done) / Double(s.total) : 0) { Text("Installing \(s.done)/\(s.total)…").font(.subheadline) }
        case let s as LibraryPackStateInstalled:
            HStack {
                if s.updateAvailable, let info {
                    Text("Installed · \(s.books) books · a new version is available (\(info.books) books)").font(.subheadline)
                } else {
                    Text("Installed · \(s.books) books").font(.subheadline)
                }
                Spacer()
                if s.updateAvailable { Button("Update") { store.downloadLibrary(lang) }.buttonStyle(.borderless).font(.subheadline) }
                Button("Remove books") { store.deleteLibrary(lang) }.buttonStyle(.borderless).font(.subheadline)
            }
        case let s as LibraryPackStateFailed:
            HStack {
                Text(PackErrors.text(s.error)).font(.subheadline).foregroundStyle(.red)
                Spacer()
                Button("Retry") { store.downloadLibrary(lang) }.buttonStyle(.borderless).font(.subheadline)
                    .accessibilityIdentifier("library.retry.\(lang)")
            }
        default:
            if let info {
                HStack {
                    Text("Not downloaded · \(libraryAvailability(info))").font(.subheadline)
                    Spacer()
                    Button("Download") { store.downloadLibrary(lang) }.buttonStyle(.bordered).font(.subheadline)
                        .accessibilityIdentifier("library.download.\(lang)")
                }
            } else {
                Text(libraryAvailability(nil)).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

/// Modelo de traducción de un idioma (ML Kit, ~40 MB): descargado o no, descarga en curso o fallida, descargar y borrar.
/// El modelo inglés es el pivote de ML Kit y no se puede borrar.
struct TranslationModelRow: View {
    @EnvironmentObject private var store: AppStore
    let code: String
    let deletable: Bool

    var body: some View {
        let installed = store.translationModels?.downloaded.contains(code) ?? false
        let download = store.translationModels?.downloads[code]
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                if installed {
                    Text("\(Languages.name(code)) translator — downloaded").font(.subheadline)
                } else {
                    Text("\(Languages.name(code)) translator — not downloaded").font(.subheadline)
                }
                Spacer()
                if download is TranslationModelDownloadRunning {
                    ProgressView()
                } else if installed, deletable, code != "en" {
                    Button("Delete") { Task { try? await LinguaShared.shared.deleteTranslationModel(code: code) } }
                        .buttonStyle(.borderless).font(.subheadline)
                } else if !installed {
                    Button(download == nil ? "Download" : "Retry") { LinguaShared.shared.downloadTranslationModel(code: code) }
                        .buttonStyle(.borderless).font(.subheadline)
                }
            }
            if let failed = download as? TranslationModelDownloadFailed {
                Text(failed.wifiOnly ? "Download failed (Wi-Fi only): \(failed.detail)" : "Download failed: \(failed.detail)")
                    .font(.footnote).foregroundStyle(.red)
            }
        }
        .accessibilityIdentifier("translation.model.\(code)")
    }
}
