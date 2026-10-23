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
                    } else {
                        Text(unavailable(card.lookup.word)).foregroundStyle(.secondary)
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
                }
                if let match = card.lookup.expression {
                    Section("Expression") {
                        Text(match.surface).bold()
                        Text(match.entry.lemma).font(.footnote).foregroundStyle(.secondary)
                        if let expression { Text(expression) }
                    }
                }
                if let examples = (card.lookup.word as? TranslationResultTranslated)?.examples, !examples.isEmpty {
                    Section("Examples") {
                        ForEach(Array(examples.enumerated()), id: \.offset) { _, ex in
                            VStack(alignment: .leading) {
                                Text(ex.text)
                                if let t = ex.translation { Text(t).font(.footnote).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
                Section("In the sentence") {
                    Text(card.lookup.sentence.text).font(.callout)
                    if let sentenceTranslation { Text(sentenceTranslation).font(.callout).foregroundStyle(.secondary) }
                }
            }
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
                    Button(card.pausedByTap ? "Resume" : "Close") { Task { await model.resumeAfterCard() } }
                }
            }
        }
    }

    private func unavailable(_ result: TranslationResult) -> String {
        guard let reason = (result as? TranslationResultUnavailable)?.reason else { return "No translation available for this word." }
        if reason == TranslationResultReason.sameLanguage { return "Same language as the book." }
        if reason == TranslationResultReason.modelMissing { return "The translator for this language is not downloaded yet." }
        if reason == TranslationResultReason.languagePairUnsupported { return "This language pair is not supported." }
        return "No translation available for this word."
    }
}

struct SentenceCardSheet: View {
    let card: SentenceCard
    @ObservedObject var model: ReaderModel

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(card.sentence.text)
                    Button { Task { await model.speak(card.sentence.text) } } label: { Label("Listen", systemImage: "speaker.wave.2") }
                }
                Section("Translation") {
                    Text(card.translation ?? "No translation available for this sentence.").foregroundStyle(card.translation == nil ? .secondary : .primary)
                }
            }
            .navigationTitle("Sentence")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { Task { await model.resumeAfterCard() } } } }
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
                        }
                    case 1:
                        ForEach(model.bookmarks, id: \.id) { b in
                            Button {
                                Task { await model.jump(toPassage: Int(b.passageIndex), offset: Int(b.charOffset)); dismiss() }
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(b.excerpt).lineLimit(2)
                                    Text("Passage \(b.passageIndex + 1)").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .swipeActions { Button(role: .destructive) { Task { try? await LinguaShared.shared.deleteBookmark(id: b.id) } } label: { Label("Delete", systemImage: "trash") } }
                        }
                    default:
                        ForEach(store.vocabulary.filter { $0.bookId == model.bookId }, id: \.id) { v in
                            VStack(alignment: .leading) {
                                Text("\(v.term) — \(v.translation ?? "…")")
                                Text(v.context).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                            }
                        }
                    }
                }
                .listStyle(.plain)
            }
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
                    Section("Text") {
                        Picker("Theme", selection: Binding(get: { p.theme }, set: { t in Task { try? await LinguaShared.shared.setTheme(theme: t) } })) {
                            Text("System").tag(ThemeMode.system); Text("Light").tag(ThemeMode.light); Text("Dark").tag(ThemeMode.dark); Text("Sepia").tag(ThemeMode.sepia)
                        }
                        HStack {
                            Text("Size")
                            Slider(value: Binding(get: { Double(p.fontScale) }, set: { v in Task { try? await LinguaShared.shared.setFontScale(scale: Float(v)) } }), in: 0.8...1.8, step: 0.1)
                            Text("\(Int(p.fontScale * 100)) %").monospacedDigit().font(.footnote)
                        }
                        HStack {
                            Text("Line spacing")
                            Slider(value: Binding(get: { Double(p.lineHeight) }, set: { v in Task { try? await LinguaShared.shared.setLineHeight(lineHeight: Float(v)) } }), in: 1.2...2.2, step: 0.1)
                        }
                        Picker("Alignment", selection: Binding(get: { p.textJustification }, set: { j in Task { try? await LinguaShared.shared.setTextJustification(justification: j) } })) {
                            Text("Left").tag(TextJustification.left); Text("Justified").tag(TextJustification.justified); Text("Justified + hyphens").tag(TextJustification.justifiedHyphenated)
                        }
                    }
                    Section("Reading") {
                        Picker("Translation", selection: Binding(get: { p.displayMode }, set: { m in Task { try? await LinguaShared.shared.setDisplayMode(mode: m) } })) {
                            Text("On tap").tag(DisplayMode.translationOnTap); Text("Under each sentence").tag(DisplayMode.translationBelow)
                        }
                        Picker("Highlight", selection: Binding(get: { p.highlightStyle }, set: { h in Task { try? await LinguaShared.shared.setHighlightStyle(style: h) } })) {
                            Text("Background").tag(HighlightStyle.background); Text("Underline").tag(HighlightStyle.underline); Text("Marker").tag(HighlightStyle.marker)
                        }
                        Toggle("Turn pages automatically", isOn: Binding(get: { p.autoScroll }, set: { v in Task { try? await LinguaShared.shared.setAutoScroll(enabled: v) } }))
                        Toggle("Keep screen on", isOn: Binding(get: { p.keepScreenOn }, set: { v in Task { try? await LinguaShared.shared.setKeepScreenOn(enabled: v) } }))
                        Toggle("Pause when looking up a word", isOn: Binding(get: { p.pauseOnWordTap }, set: { v in Task { try? await LinguaShared.shared.setPauseOnWordTap(enabled: v) } }))
                        Toggle("Continue with the next passage", isOn: Binding(get: { p.autoContinue }, set: { v in Task { try? await LinguaShared.shared.setAutoContinue(enabled: v) } }))
                    }
                    Section("Voice") {
                        Toggle("Learning mode with voice", isOn: Binding(get: { p.ttsMode }, set: { v in Task { try? await LinguaShared.shared.setTtsMode(enabled: v) } }))
                        Picker("Audio mode", selection: Binding(get: { model.engineId }, set: { id in Task { await model.selectEngine(id) } })) {
                            Text("Live voice").tag("apple-tts")
                            Text("Cached audio (experimental)").tag("apple-tts-cached")
                        }
                        .accessibilityIdentifier("settings.engine")
                        NavigationLink("Audio diagnostics") { DiagnosticsView() }
                        Picker("Translate to", selection: Binding(get: { Languages.primary(p.translationLanguage) }, set: { l in Task {
                            try? await LinguaShared.shared.setTranslationLanguage(tag: l, wifiOnly: p.translationWifiOnly)
                            LinguaShared.shared.downloadTranslationModelsFor(sourceLanguage: model.book?.learningLanguage, targetLanguage: l)
                        } })) {
                            ForEach(Languages.translationTargets, id: \.self) { Text(Languages.name($0)).tag($0) }
                        }
                        HStack {
                            Text("Pause between sentences")
                            Slider(value: Binding(get: { Double(p.sentencePauseMs) }, set: { v in Task { try? await LinguaShared.shared.setSentencePause(ms: Int32(v)) } }), in: 200...1500, step: 50)
                            Text("\(Double(p.sentencePauseMs) / 1000, specifier: "%.1f") s").font(.footnote).monospacedDigit()
                        }
                    }
                }
            }
            .navigationTitle("Reading settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

struct AboutSheet: View {
    @ObservedObject var model: ReaderModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let book = model.book {
                    Section {
                        Text(book.title).font(.headline)
                        if let d = book.description_ { Text(d).font(.footnote) }
                    }
                    Section("Details") {
                        row("Authors", book.authors.joined(separator: ", "))
                        row("Language", Languages.name(book.learningLanguage))
                        row("Level", book.cefrLevel.name)
                        if let genre = book.genre { row("Genre", genre) }
                        row("Chapters", "\(book.chapters.count)")
                        row("Passages", "\(book.passages.count)")
                        row("Words", "\(book.wordCount)")
                        row("Glossary entries", "\(book.glossary.count)")
                    }
                    Section("Rights") {
                        if let credit = book.adaptationCredit { row("Adaptation", credit) }
                        row("License", book.license.name ?? book.license.id)
                        row("Source", book.sourceAttribution)
                        row("Identifier", book.id)
                    }
                }
            }
            .navigationTitle("About this book")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) { Text(label).foregroundStyle(.secondary); Spacer(); Text(value).multilineTextAlignment(.trailing) }
    }
}
