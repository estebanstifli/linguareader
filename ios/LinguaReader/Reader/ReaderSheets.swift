import SwiftUI
import LinguaShared

/// Ficha de palabra: traducción, lema, expresión, ejemplos, frase; guardar en vocabulario y leer desde aquí.
struct WordCardSheet: View {
    let card: WordCard
    @ObservedObject var model: ReaderModel
    @Environment(\.dismiss) private var dismiss

    private var translation: String? { (card.lookup.word as? TranslationResultTranslated)?.text }
    private var expression: String? { (card.lookup.expressionTranslation as? TranslationResultTranslated)?.text }
    private var sentenceTranslation: String? { (card.lookup.sentenceTranslation as? TranslationResultTranslated)?.text }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(alignment: .firstTextBaseline) {
                        Text(card.token.text).font(.title2.bold())
                        Spacer()
                        Button { Task { await model.speak(card.token.text) } } label: { Image(systemName: "speaker.wave.2") }
                    }
                    if let translation {
                        Text(translation).font(.title3)
                        if let pos = (card.lookup.word as? TranslationResultTranslated)?.partOfSpeech { Text(pos).font(.footnote).foregroundStyle(.secondary) }
                        if TranslationLabels.isMachine(card.lookup.word) { Text("Machine translation").font(.caption2).foregroundStyle(.secondary) }
                    } else {
                        Text(TranslationLabels.unavailable(card.lookup.word, target: model.translationLanguage)).foregroundStyle(.secondary)
                        if (card.lookup.word as? TranslationResultUnavailable)?.reason == TranslationResultReason.modelMissing {
                            if model.translatorDownloading {
                                HStack { ProgressView(); Text("Downloading the translator…").font(.footnote).foregroundStyle(.secondary) }
                            } else {
                                Button { Task { await model.downloadTranslators(card) } } label: { Label("Download translator (about 30 MB per language)", systemImage: "arrow.down.circle") }
                                    .accessibilityIdentifier("word.downloadTranslator")
                            }
                        }
                    }
                    if let lemma = card.lookup.wordLemma, lemma != card.token.text { Text("Base form: \(lemma)").font(.footnote).foregroundStyle(.secondary) }
                }.themedRows()
                if let match = card.lookup.expression {
                    Section("Expression") {
                        Text(match.surface).bold()
                        Text(match.entry.lemma).font(.footnote).foregroundStyle(.secondary)
                        if let expression { Text(expression) }
                    }.themedRows()
                }
                if let examples = (card.lookup.word as? TranslationResultTranslated)?.examples, !examples.isEmpty {
                    Section("Examples") {
                        ForEach(Array(examples.enumerated()), id: \.offset) { _, ex in
                            VStack(alignment: .leading) {
                                Text(ex.text)
                                if let t = ex.translation { Text(t).font(.footnote).foregroundStyle(.secondary) }
                            }
                        }.themedRows()
                    }.themedRows()
                }
                Section("In the sentence") {
                    Text(card.lookup.sentence.text).font(.callout)
                    if let sentenceTranslation {
                        Text(sentenceTranslation).font(.callout).foregroundStyle(.secondary)
                        if TranslationLabels.isMachine(card.lookup.sentenceTranslation) { Text("Machine translation").font(.caption2).foregroundStyle(.secondary) }
                    }
                    Button { Task { await model.openSentenceCard(card) } } label: { Label("Listen to sentence", systemImage: "speaker.wave.2") }
                }.themedRows()
            }
            .themedScreen()
            .navigationTitle("Word")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .bottomBar) {
                    Button { Task { await model.saveWord(card) } } label: { Label(card.saved ? "Saved" : "Save", systemImage: card.saved ? "star.fill" : "star") }
                        .disabled(card.saved)
                        .accessibilityIdentifier("word.save")
                    Spacer()
                    Button { Task { await model.readFrom(chapterOffset: model.layout?.chapterOffset(passageIndex: card.passageIndex, offset: Int(card.token.rangeStart)) ?? 0) } } label: { Label("Read from here", systemImage: "play") }
                    Spacer()
                    if card.pausedByTap {
                        Button("Resume") { Task { await model.resumeAfterCard() } }
                    } else {
                        Button("Close") { Task { await model.resumeAfterCard() } }
                    }
                }
            }
        }
    }
}

/// Hoja de frase (pulsación larga en el texto, o «Listen to sentence» en la ficha de palabra): la frase, escucharla y
/// su traducción, con «Machine translation» si la hizo el traductor del dispositivo.
struct SentenceCardSheet: View {
    let card: SentenceCard
    @ObservedObject var model: ReaderModel

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(card.sentence.text)
                    Button { Task { await model.speakSentence(card) } } label: { Label("Listen to sentence", systemImage: "speaker.wave.2") }
                        .accessibilityIdentifier("sentence.listen")
                }.themedRows()
                Section("Translation") {
                    if let translation = card.translation {
                        Text(translation)
                        if card.machine { Text("Machine translation").font(.caption2).foregroundStyle(.secondary) }
                    } else {
                        Text("No translation available for this sentence.").foregroundStyle(.secondary)
                    }
                }.themedRows()
            }
            .themedScreen()
            .navigationTitle("Sentence")
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("sentence.sheet")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    if card.pausedByTap {
                        Button("Resume") { Task { await model.resumeAfterCard() } }
                    } else {
                        Button("Close") { Task { await model.resumeAfterCard() } }
                    }
                }
            }
        }
    }
}

/// Índice: capítulos, marcadores y vocabulario del libro.
struct IndexSheet: View {
    @ObservedObject var model: ReaderModel
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0

    var body: some View {
        NavigationStack {
            VStack {
                Picker("", selection: $tab) {
                    Text("Chapters").tag(0)
                    Text("Bookmarks (\(model.bookmarks.count))").tag(1)
                    Text("Vocabulary").tag(2)
                }
                .pickerStyle(.segmented).padding(.horizontal)
                List {
                    switch tab {
                    case 0:
                        ForEach(Array((model.book?.chapters ?? []).enumerated()), id: \.offset) { i, chapter in
                            Button {
                                Task { await model.jump(toChapter: i); dismiss() }
                            } label: {
                                HStack {
                                    Text(chapter.title).fontWeight(i == model.chapterIndex ? .bold : .regular)
                                    Spacer()
                                    Text("\(chapter.passages.count) passages").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }.themedRows()
                    case 1:
                        ForEach(model.bookmarks, id: \.id) { b in
                            Button {
                                Task { await model.jump(toPassage: Int(b.passageIndex), offset: Int(b.charOffset)); dismiss() }
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(b.excerpt).lineLimit(2)
                                    Text("Passage \(Int(b.passageIndex) + 1)").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .swipeActions { Button(role: .destructive) { Task { try? await LinguaShared.shared.deleteBookmark(id: b.id) } } label: { Label("Delete", systemImage: "trash") } }
                        }.themedRows()
                    default:
                        ForEach(store.vocabulary.filter { $0.bookId == model.bookId }, id: \.id) { v in
                            VStack(alignment: .leading) {
                                Text("\(v.term) — \(v.translation ?? "…")")
                                Text(v.context).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                            }
                        }.themedRows()
                    }
                }
                .listStyle(.plain)
            }
            .themedScreen()
            .navigationTitle("Contents")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
        }
    }
}

struct SearchSheet: View {
    @ObservedObject var model: ReaderModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var hits: [SearchHit] = []

    var body: some View {
        NavigationStack {
            List(hits) { hit in
                Button {
                    Task { await model.jump(toPassage: hit.passageIndex, offset: hit.offset); dismiss() }
                } label: {
                    VStack(alignment: .leading) {
                        Text(hit.snippet).lineLimit(3)
                        Text(hit.chapterTitle).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .overlay { if hits.isEmpty && query.count >= 2 { ContentUnavailableView.search(text: query) } }
            .searchable(text: $query, prompt: "Search in this book")
            .onChange(of: query) { _, q in hits = model.search(q) }
            .themedScreen()
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
        }
    }
}

/// Ajustes de lectura (aspecto, modo, voz) sin salir del libro.
struct ReaderSettingsSheet: View {
    @ObservedObject var model: ReaderModel
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                if let p = store.prefs {
                    TextPreferencesSection(p: p)
                    ReadingPreferencesSection(p: p)
                    Section("Voice") {
                        Picker("Audio mode", selection: Binding(get: { model.engineId }, set: { id in Task { await model.selectEngine(id) } })) {
                            Text("Live voice").tag("apple-tts")
                            Text("Cached audio (experimental)").tag("apple-tts-cached")
                        }
                        .accessibilityIdentifier("settings.engine")
                        NavigationLink("Audio diagnostics") { DiagnosticsView() }
                        VoicePreferencesRows(p: p, sourceLanguage: model.book?.learningLanguage)
                    }.themedRows()
                }
            }
            .themedScreen()
            .navigationTitle("Reading settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

/// «Acerca del documento», con las mismas filas que Android: datos, origen, tiempo de escucha, progreso,
/// traducciones incluidas, estanterías y derechos (con enlace a la licencia si lo hay).
struct AboutSheet: View {
    @ObservedObject var model: ReaderModel
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let book = model.book {
                    Section {
                        Text(book.title).font(.headline)
                        if let d = book.description_ { Text(d).font(.footnote) }
                    }.themedRows()
                    Section("Details") {
                        if !book.authors.isEmpty { row("Authors", book.authors.joined(separator: ", ")) }
                        row("Language", Languages.name(book.learningLanguage))
                        row("Level", book.cefrLevel.name)
                        if let genre = book.genre { row("Genre", genre) }
                        if store.isLibraryBook(book.id) {
                            row("Origin", String(localized: "Graded readers library"))
                        } else {
                            row("Origin", String(localized: "Imported by you"))
                        }
                        row("Chapters", "\(book.chapters.count)")
                        row("Passages", "\(book.passages.count)")
                        row("Pages (at the current text size)", String(localized: "\(model.pageCount) in this chapter"))
                        row("Words", "\(book.wordCount)")
                        row("Estimated read-aloud time", String(localized: "≈ \(Int(Book.companion.estimateMinutes(words: book.wordCount, level: book.cefrLevel))) min"))
                        if let fraction = store.progressFraction(book.id) {
                            row("Progress", String(localized: "\(Int(fraction * 100)) % · passage \(model.passageIndex + 1) of \(book.passages.count)"))
                        } else {
                            row("Progress", String(localized: "Not started"))
                        }
                        let included = book.fullyTranslatedLanguages.map { Languages.name($0) }.sorted()
                        if !included.isEmpty { row("Included translations", included.joined(separator: ", ")) }
                        if book.glossary.count > 0 { row("Glossary entries", "\(book.glossary.count)") }
                        let shelves = (store.shelves?.shelves ?? []).filter { $0.bookIds.contains(book.id) }.map(Shelves.title)
                        if !shelves.isEmpty { row("In", shelves.joined(separator: ", ")) }
                    }.themedRows()
                    Section("Rights") {
                        if let credit = book.adaptationCredit { row("Adaptation", credit) }
                        row("License", book.license.name ?? book.license.id)
                        if let link = book.license.url, let url = URL(string: link) {
                            Link(destination: url) { row("License (link)", link) }
                        }
                        if !book.sourceAttribution.isEmpty { row("Source", book.sourceAttribution) }
                        row("Identifier", book.id)
                    }.themedRows()
                }
            }
            .themedScreen()
            .navigationTitle("About this document")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
        }
    }

    private func row(_ label: LocalizedStringKey, _ value: String) -> some View {
        HStack(alignment: .top) { Text(label).foregroundStyle(.secondary); Spacer(); Text(value).multilineTextAlignment(.trailing) }
    }
}
