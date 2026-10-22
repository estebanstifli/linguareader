import SwiftUI
import LinguaShared

/// Palabras guardadas desde el lector, con su traducción y la frase en la que aparecían.
struct VocabularyView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        Group {
            if store.vocabulary.isEmpty {
                ContentUnavailableView("No saved words yet", systemImage: "textformat.abc", description: Text("Tap a word while reading and save it to see it here."))
            } else {
                List {
                    ForEach(store.vocabulary, id: \.id) { entry in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(entry.term).font(.headline)
                            Text(entry.translation ?? "No translation into \(Languages.name(entry.targetLanguage))")
                                .foregroundStyle(entry.translation == nil ? .secondary : .primary)
                            if let lemma = entry.lemma, lemma != entry.term {
                                Text(lemma).font(.subheadline).foregroundStyle(.secondary)
                            }
                            Text("“\(entry.context)”").font(.subheadline).italic().foregroundStyle(.secondary)
                            Text("\(entry.bookTitle) · \(Languages.name(entry.sourceLanguage)) → \(Languages.name(entry.targetLanguage))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                    .onDelete { offsets in offsets.map { store.vocabulary[$0].id }.forEach { store.deleteVocabulary($0) } }
                }
            }
        }
        .navigationTitle("Vocabulary")
    }
}
