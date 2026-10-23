import SwiftUI
import LinguaShared

/// Ajustes › Translation: idioma de traducción, «solo Wi-Fi» y los modelos de ML Kit (~30 MB por idioma) con
/// descarga, reintento y borrado; misma lógica que la sección de Android.
struct TranslationView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        List {
            if let p = store.prefs {
                Section {
                    Picker("Translate to", selection: Binding(get: { Languages.primary(p.translationLanguage) }, set: { l in
                        Task {
                            try? await LinguaShared.shared.setTranslationLanguage(tag: l, wifiOnly: p.translationWifiOnly)
                            LinguaShared.shared.downloadTranslationModelsFor(sourceLanguage: p.readingLanguage, targetLanguage: l)
                        }
                    })) {
                        ForEach(Languages.translationTargets, id: \.self) { Text(Languages.name($0)).tag($0) }
                    }
                    Toggle("Download translators only over Wi-Fi", isOn: Binding(get: { p.translationWifiOnly }, set: { v in
                        Task { try? await LinguaShared.shared.setTranslationLanguage(tag: p.translationLanguage, wifiOnly: v) }
                    }))
                } footer: {
                    Text("Translating needs the model of the book's language and of your language. Words and sentences are translated on the phone, without sending the text anywhere.")
                }
            }
            Section("Translation models (about 30 MB each)") {
                if let state = store.translationModels {
                    ForEach(state.supported, id: \.self) { code in
                        TranslationModelRow(code: code, installed: state.downloaded.contains(code), download: state.downloads[code])
                    }
                } else {
                    Text("The translator is not available in this build.").foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Translation")
    }
}

private struct TranslationModelRow: View {
    let code: String
    let installed: Bool
    let download: TranslationModelDownload?

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(Languages.name(code))
                if let failed = download as? TranslationModelDownloadFailed {
                    Text(failed.wifiOnly ? "Download failed (Wi-Fi only): \(failed.detail)" : "Download failed: \(failed.detail)")
                        .font(.footnote).foregroundStyle(.red)
                }
            }
            Spacer()
            if download is TranslationModelDownloadRunning {
                ProgressView()
            } else if installed {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            } else {
                Button(download == nil ? "Download" : "Retry") { LinguaShared.shared.downloadTranslationModel(code: code) }
                    .buttonStyle(.bordered)
            }
        }
        .swipeActions {
            if installed {
                Button(role: .destructive) { Task { try? await LinguaShared.shared.deleteTranslationModel(code: code) } } label: { Label("Delete", systemImage: "trash") }
            }
        }
        .accessibilityIdentifier("translation.model.\(code)")
    }
}
