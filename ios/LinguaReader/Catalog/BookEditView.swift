import SwiftUI
import LinguaShared

/// Corrige título, autor, idioma y nivel de un libro; el original no se toca (ver `BookOverrideEntity`).
struct BookEditView: View {
    @Environment(\.dismiss) private var dismiss
    let bookId: String
    @State private var title = ""
    @State private var author = ""
    @State private var language = "en"
    @State private var level: CefrLevel = .b1
    @State private var loaded = false
    @State private var edited = false
    @State private var failed = false

    private let languages = Languages.translationTargets + ["nl", "ca"]

    var body: some View {
        Form {
            if failed {
                Text("This book could not be loaded.").foregroundStyle(.red)
            } else if !loaded {
                ProgressView()
            } else {
                Section {
                    TextField("Title", text: $title).accessibilityIdentifier("edit.title")
                    TextField("Author", text: $author)
                }
                Section("Language of the text") {
                    Picker("Language", selection: $language) {
                        ForEach(languages, id: \.self) { Text(Languages.name($0)).tag($0) }
                    }
                }
                Section("Level") {
                    Picker("Level", selection: $level) {
                        ForEach([CefrLevel.a1, .a2, .b1, .b2, .c1, .c2], id: \.self) { Text($0.name).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                if edited {
                    Button("Reset to original data", role: .destructive) {
                        Task { try? await LinguaShared.shared.library.resetBook(bookId: bookId); dismiss() }
                    }
                }
            }
        }
        .navigationTitle("Edit book")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    Task {
                        try? await LinguaShared.shared.library.editBook(bookId: bookId, title: title, author: author, language: language, level: level.name)
                        dismiss()
                    }
                }
                .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .task {
            guard let summary = try? await LinguaShared.shared.loadSummary(bookId: bookId) else { failed = true; return }
            title = summary.title
            author = summary.authors.joined(separator: ", ")
            language = languages.contains(Languages.primary(summary.learningLanguage)) ? Languages.primary(summary.learningLanguage) : summary.learningLanguage
            level = summary.cefrLevel
            edited = (try? await LinguaShared.shared.library.hasEdits(bookId: bookId))?.boolValue ?? false
            loaded = true
        }
    }
}
