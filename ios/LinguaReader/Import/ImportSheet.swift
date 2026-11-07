import SwiftUI
import LinguaShared

/// Hoja de importación (como `ImportDialog` en Android): progreso de lectura, revisión de los datos del libro y errores.
struct ImportSheet: View {
    @ObservedObject var model: ImportModel
    @EnvironmentObject private var store: AppStore
    /// Libro recién importado: el catálogo abre su ficha.
    let onDone: (String) -> Void

    var body: some View {
        NavigationStack {
            Group {
                switch model.state {
                case .idle, .done:
                    EmptyView()
                case .reading(let label):
                    VStack(spacing: 16) {
                        ProgressView().controlSize(.large)
                        Text(label).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                    .padding(32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .navigationTitle("Importing")
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { model.dismiss() } } }
                case .failed(let message, let detail):
                    VStack(alignment: .leading, spacing: 12) {
                        Label(message, systemImage: "exclamationmark.triangle").font(.headline)
                        if let detail, !detail.isEmpty {
                            Text("Technical detail: \(detail)").font(.footnote).foregroundStyle(.secondary)
                        }
                        Text("Supported: \(LinguaShared.shared.supportedImportExtensions().joined(separator: ", ")).")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .navigationTitle("Couldn't import")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { model.dismiss() } } }
                    .accessibilityIdentifier("import.failed")
                case .review(let review):
                    ReviewForm(review: review, model: model)
                        .navigationTitle("Import book")
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { model.dismiss() }.disabled(review.saving) }
                            ToolbarItem(placement: .confirmationAction) {
                                Button { model.confirm() } label: {
                                    if review.saving { Text("Saving…") } else { Text("Import") }
                                }
                                .disabled(review.saving)
                                .accessibilityIdentifier("import.confirm")
                            }
                        }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .themedScreen()
        }
        .interactiveDismissDisabled()
        .onChange(of: doneId) { _, id in
            if let id {
                model.dismiss()
                onDone(id)
            }
        }
    }

    private var doneId: String? {
        if case .done(let id) = model.state { return id }
        return nil
    }
}

private struct ReviewForm: View {
    let review: ImportModel.Review
    @ObservedObject var model: ImportModel

    var body: some View {
        let document = review.draft.document
        Form {
            Section {
                Text("\(review.draft.sourceName) · \(document.format.uppercased()) · \(Int(document.chapters.count)) chapters · \(Int(document.paragraphCount)) paragraphs")
                    .font(.footnote).foregroundStyle(.secondary)
                if review.draft.alreadyImported {
                    Text("This file is already in your library: it will be replaced.").font(.footnote).foregroundStyle(.red)
                }
                TextField("Title", text: Binding(get: { review.title }, set: { v in model.edit { $0.title = v } }))
                    .accessibilityIdentifier("import.title")
                TextField("Author", text: Binding(get: { review.author }, set: { v in model.edit { $0.author = v } }))
            }.themedRows()
            Section {
                if review.draft.detectedLanguage == nil {
                    Text("The document doesn't state its language: choose the right one so the voice and sentence splitting work well.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(review.languageOptions, id: \.self) { tag in
                            chip(Languages.name(tag), selected: Languages.primary(review.language) == Languages.primary(tag)) { model.edit { $0.language = tag } }
                                .accessibilityIdentifier("import.language.\(Languages.primary(tag))")
                        }
                    }
                }
            } header: { Text("Text language") }.themedRows()
            if review.canTranslate {
                Section {
                    choice(selected: !review.translateNow, title: String(localized: "While I read"),
                           detail: String(localized: "Each passage is translated when you reach it. You can start reading right away.")) { model.edit { $0.translateNow = false } }
                    choice(selected: review.translateNow, title: String(localized: "Translate the whole book now"),
                           detail: String(localized: "In the background and on the device; it takes time depending on the size (\(Int(document.paragraphCount)) paragraphs). Afterwards you can read without waiting or a connection.")) { model.edit { $0.translateNow = true } }
                } header: { Text("Translation into \(Languages.nameInSentence(review.translationTarget))") }.themedRows()
            }
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach([CefrLevel.a1, .a2, .b1, .b2, .c1, .c2], id: \.self) { level in
                            chip(level.name, selected: review.level == level) { model.edit { $0.level = level } }
                        }
                    }
                }
            } header: { Text("Level (CEFR)") }.themedRows()
        }
        .disabled(review.saving)
        .accessibilityIdentifier("import.review")
    }

    private func chip(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .font(.subheadline)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(selected ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.12), in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func choice(selected: Bool, title: String, detail: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle").foregroundStyle(.tint).padding(.top, 2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    Text(detail).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

/// Menú «+» del catálogo: desde un archivo, página web o texto pegado, más la pista de «Abrir en…» desde otras apps.
struct ImportMenu: View {
    @EnvironmentObject private var store: AppStore
    @ObservedObject var model: ImportModel
    @State private var pickFile = false
    @State private var askUrl = false
    @State private var url = ""

    var body: some View {
        Menu {
            Button { pickFile = true } label: { Label("From a file…", systemImage: "doc") }
                .accessibilityIdentifier("import.fromFile")
            Button { url = UIPasteboard.general.url?.absoluteString ?? ""; askUrl = true } label: { Label("From a web page…", systemImage: "globe") }
                .accessibilityIdentifier("import.fromWeb")
            Button {
                if let text = UIPasteboard.general.string, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    model.start(.text(text, title: nil), translationTarget: store.prefs?.translationLanguage ?? "en")
                }
            } label: { Label("Paste copied text", systemImage: "doc.on.clipboard") }
                .disabled(!UIPasteboard.general.hasStrings)
                .accessibilityIdentifier("import.pasteText")
            Divider()
            Text("Also: open the file in Files, Mail, WhatsApp or Telegram and choose “Open in” › LingoAudioBook.")
        } label: { Image(systemName: "plus") }
        .accessibilityLabel("Import book")
        .accessibilityIdentifier("catalog.import")
        .fileImporter(isPresented: $pickFile, allowedContentTypes: DocumentInbox.contentTypes) { result in
            if case .success(let picked) = result { model.start(.file(picked), translationTarget: store.prefs?.translationLanguage ?? "en") }
        }
        .alert("From a web page", isPresented: $askUrl) {
            TextField("https://…", text: $url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Cancel", role: .cancel) {}
            Button("Import") { model.start(.webPage(url), translationTarget: store.prefs?.translationLanguage ?? "en") }
                .disabled(url.trimmingCharacters(in: .whitespaces).isEmpty)
        } message: {
            Text("The text of the page is imported as a book. Images are not downloaded.")
        }
    }
}
