import Foundation
import SwiftUI
import MediaPlayer
import LinguaShared

/// Ficha de palabra abierta (mini o completa).
struct WordCard: Identifiable {
    let id = UUID()
    let passageIndex: Int
    let token: Token
    let lookup: WordLookup
    var saved: Bool
    var pausedByTap: Bool
}

struct SentenceCard: Identifiable {
    let id = UUID()
    let passageIndex: Int
    let sentence: Sentence
    let translation: String?
}

struct SearchHit: Identifiable {
    let id = UUID()
    let passageIndex: Int
    let offset: Int
    let chapterTitle: String
    let snippet: String
}

/// Estado del lector: libro, capítulo compuesto, página, reproducción y fichas. El audio lo lleva el
/// `ReaderAudioController` compartido (misma lógica que Android); aquí se traduce a SwiftUI y TextKit.
@MainActor
final class ReaderModel: ObservableObject {
    @Published private(set) var book: Book?
    @Published private(set) var passageIndex = 0
    @Published private(set) var chapterIndex = 0
    @Published private(set) var layout: ChapterLayout?
    @Published var pageIndex = 0
    @Published private(set) var pageOffsets: [Int] = []
    @Published private(set) var playback: PlaybackState?
    @Published private(set) var bookmarks: [BookmarkEntity] = []
    @Published private(set) var voices: [VoiceInfo] = []
    @Published var showOffset: Int?
    @Published var controlsVisible = true
    @Published var wordCard: WordCard?
    @Published var sentenceCard: SentenceCard?
    @Published var showFullCard = false
    @Published var error: String?

    let bookId: String
    private var watchers: [FlowWatcher] = []
    private var store: AppStore?
    private var completionHandledFor: String?
    private var remoteTargets: [Any] = []
    private var lastStyle: ChapterLayout.Style?
    private var systemDark = false

    private var audio: ReaderAudioController { LinguaShared.shared.audio }

    init(bookId: String) {
        self.bookId = bookId
    }

    var prefs: UserPreferences? { store?.prefs }
    var passage: Passage? { book.map { b in Int(passageIndex) < b.passages.count ? b.passages[passageIndex] : nil } ?? nil }
    var chapter: Chapter? { book.map { b in chapterIndex < b.chapters.count ? b.chapters[chapterIndex] : nil } ?? nil }
    var isPlaying: Bool { playback?.status == .playing }
    var ttsMode: Bool { prefs?.ttsMode ?? true }
    var translationLanguage: String { prefs?.translationLanguage ?? "es" }
    var pageCount: Int { max(1, pageOffsets.count) }
    var hasNextPassage: Bool { book.map { passageIndex + 1 < $0.passages.count } ?? false }

    func theme(systemDark: Bool) -> ReaderTheme { ReaderTheme.resolve(prefs?.theme, systemDark: systemDark) }

    // MARK: Ciclo de vida

    func start(store: AppStore, systemDark: Bool) async {
        self.store = store
        self.systemDark = systemDark
        if book != nil { rebuildLayoutIfNeeded(); return }
        watchers = [
            LinguaShared.shared.watchPlayback { [weak self] state in self?.onPlayback(state) },
            LinguaShared.shared.watchBookmarks(bookId: bookId) { [weak self] list in self?.bookmarks = list },
        ]
        do {
            let loaded = try await LinguaShared.shared.books.loadBook(bookId: bookId)
            book = loaded
            let progress = try? await LinguaShared.shared.loadProgress(bookId: bookId)
            let index = progress.map { Int($0.passageIndex) }.flatMap { $0 < loaded.passages.count ? $0 : nil } ?? 0
            let offset = progress.map { Int($0.charOffset) } ?? 0
            await open(passageIndex: index, offset: offset, autoplay: false, scroll: true)
            setupRemoteCommands()
            voices = (try? await LinguaShared.shared.listVoices(languageTag: loaded.learningLanguage)) ?? []
        } catch {
            self.error = error.localizedDescription
        }
    }

    func stop() async {
        await persist()
        try? await audio.stop()
        watchers.forEach { $0.close() }
        watchers = []
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func onSystemDarkChanged(_ dark: Bool) {
        systemDark = dark
        rebuildLayoutIfNeeded()
    }

    // MARK: Capítulo y páginas

    private var style: ChapterLayout.Style {
        var s = ChapterLayout.Style()
        if let p = prefs {
            s.fontScale = CGFloat(p.fontScale)
            s.lineHeight = CGFloat(p.lineHeight)
            s.justification = p.textJustification
            s.showTranslations = p.displayMode == .translationBelow
            s.translationLanguage = p.translationLanguage
            s.themeId = p.theme.name
        }
        s.themeId += systemDark ? "-dark" : "-light"
        return s
    }

    func rebuildLayoutIfNeeded() {
        guard let book, chapterIndex < book.chapters.count else { return }
        let style = self.style
        if layout?.chapterIndex == chapterIndex, lastStyle == style { return }
        lastStyle = style
        let anchor = currentReadingOffset()
        layout = ChapterLayout.build(book: book, chapterIndex: chapterIndex, style: style, theme: theme(systemDark: systemDark))
        if let anchor, let offset = layout?.chapterOffset(passageIndex: anchor.passageIndex, offset: anchor.offset) { showOffset = offset }
        UIApplication.shared.isIdleTimerDisabled = prefs?.keepScreenOn ?? false
    }

    func pagesResolved(_ offsets: [Int]) {
        pageOffsets = offsets
        if let showOffset, let page = page(forChapterOffset: showOffset) { pageIndex = page }
    }

    private func page(forChapterOffset offset: Int) -> Int? {
        guard !pageOffsets.isEmpty else { return nil }
        var page = 0
        for (i, start) in pageOffsets.enumerated() where start <= offset { page = i }
        return page
    }

    /// Dónde se está leyendo: la voz si está en este capítulo, si no el principio de la página visible.
    private func currentReadingOffset() -> (passageIndex: Int, offset: Int)? {
        if let playback, let passage, playback.passageId == passage.id {
            return (passageIndex, Int(playback.resumeOffset))
        }
        guard let layout, pageIndex < pageOffsets.count else { return passage.map { (passageIndex, 0) } }
        return layout.location(chapterOffset: pageOffsets[pageIndex])
    }

    func nextPage() {
        guard let book else { return }
        if pageIndex + 1 < pageOffsets.count {
            pageIndex += 1
            pageTurned()
        } else if chapterIndex + 1 < book.chapters.count {
            showChapter(chapterIndex + 1, atEnd: false)
        }
    }

    func previousPage() {
        guard chapterIndex < (book?.chapters.count ?? 0) else { return }
        if pageIndex > 0 {
            pageIndex -= 1
            pageTurned()
        } else if chapterIndex > 0 {
            showChapter(chapterIndex - 1, atEnd: true)
        }
    }

    private func showChapter(_ index: Int, atEnd: Bool) {
        guard let book, index < book.chapters.count else { return }
        chapterIndex = index
        lastStyle = nil
        layout = ChapterLayout.build(book: book, chapterIndex: index, style: style, theme: theme(systemDark: systemDark))
        pageOffsets = []
        pageIndex = atEnd ? Int.max / 2 : 0
        showOffset = atEnd ? layout?.attributed.length ?? 0 : 0
        if let first = book.chapters[index].passages.first, !isPlaying {
            Task { await open(passageIndex: Int(first.index), offset: 0, autoplay: false, scroll: false) }
        }
    }

    /// Al pasar página en pausa, el punto de lectura (y el pasaje cargado en el audio) pasa al principio de la página.
    private func pageTurned() {
        guard !isPlaying, let layout, pageIndex < pageOffsets.count,
              let loc = layout.location(chapterOffset: pageOffsets[pageIndex]) else { return }
        showOffset = nil
        Task { await open(passageIndex: loc.passageIndex, offset: loc.offset, autoplay: false, scroll: false) }
    }

    // MARK: Pasajes y audio

    func open(passageIndex index: Int, offset: Int, autoplay: Bool, scroll: Bool, continuation: Bool = false) async {
        guard let book, index >= 0, index < book.passages.count else { return }
        let passage = book.passages[index]
        passageIndex = index
        completionHandledFor = nil
        wordCard = nil
        sentenceCard = nil
        if let chapter = book.chapterOf(passage: passage), let ci = book.chapters.firstIndex(where: { $0.id == chapter.id }), ci != chapterIndex {
            chapterIndex = ci
            lastStyle = nil
            layout = ChapterLayout.build(book: book, chapterIndex: ci, style: style, theme: theme(systemDark: systemDark))
            pageOffsets = []
        }
        if scroll, let target = layout?.chapterOffset(passageIndex: index, offset: offset) { showOffset = target }
        do {
            try await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, startOffset: Int32(offset))
            await persist(passage: passage, offset: offset)
            if autoplay && ttsMode {
                if !continuation { _ = try? await audio.warmUp(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, policy: WarmUpPolicy(targetDurationMs: 15_000, maxSentences: 1), fromSentence: 0) }
                try await audio.play()
            }
            if ttsMode {
                let upcoming = PrefetchWindow.shared.after(passages: book.passages, currentIndex: Int32(index), ahead: ReaderAudioControllerKt.PREFETCH_PAGES_AHEAD)
                if !upcoming.isEmpty { try? await audio.prefetch(passages: upcoming, languageTag: book.learningLanguage, voiceId: voiceId) }
            }
            updateNowPlaying()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private var voiceId: String? {
        guard let book, let prefs else { return nil }
        return prefs.voiceByLanguage[Languages.primary(book.learningLanguage)]
    }

    private func onPlayback(_ state: PlaybackState) {
        playback = state
        guard let passage, state.passageId == passage.id else { return }
        if state.status == .completed {
            if completionHandledFor == passage.id { return }
            completionHandledFor = passage.id
            if prefs?.autoContinue ?? true, hasNextPassage {
                Task { await open(passageIndex: passageIndex + 1, offset: 0, autoplay: true, scroll: true, continuation: true) }
            }
            return
        }
        if state.status == .playing, prefs?.autoScroll ?? true, let range = highlightRange(state) {
            showOffset = range.location
        }
        updateNowPlaying()
    }

    /// Rango del capítulo a resaltar: la palabra activa si hay tiempos, si no la frase.
    func highlightRange(_ state: PlaybackState) -> NSRange? {
        guard let passage, let layout, state.passageId == passage.id else { return nil }
        if state.granularity == .word, let id = state.activeTokenId, let token = passage.tokenById(id: id) {
            return layout.chapterRange(passageIndex: passageIndex, start: Int(token.rangeStart), end: Int(token.rangeEnd))
        }
        return nil
    }

    func sentenceRange(_ state: PlaybackState) -> NSRange? {
        guard let passage, let layout, state.passageId == passage.id, state.granularity != .none,
              Int(state.sentenceIndex) < passage.sentences.count else { return nil }
        let sentence = passage.sentences[Int(state.sentenceIndex)]
        return layout.chapterRange(passageIndex: passageIndex, start: Int(sentence.rangeStart), end: Int(sentence.rangeEnd))
    }

    var highlight: PageHighlight {
        guard let playback, playback.status == .playing || playback.status == .paused || playback.status == .ready else { return PageHighlight() }
        return PageHighlight(sentence: sentenceRange(playback), word: highlightRange(playback), style: prefs?.highlightStyle ?? .background)
    }

    func togglePlay() async {
        guard ttsMode else { try? await LinguaShared.shared.setTtsMode(enabled: true); return }
        do {
            if isPlaying { try await audio.pause(); await persist() } else {
                if let passage, let book, playback?.passageId != passage.id {
                    try await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, startOffset: 0)
                }
                try await audio.play()
            }
        } catch { self.error = error.localizedDescription }
    }

    func nextSentence() async {
        guard let passage, let playback else { return }
        let next = Int(playback.sentenceIndex) + 1
        if next < passage.sentences.count { try? await audio.seekToSentence(index: Int32(next)) }
        else if hasNextPassage { await open(passageIndex: passageIndex + 1, offset: 0, autoplay: isPlaying, scroll: true) }
    }

    func previousSentence() async {
        guard let playback else { return }
        if playback.sentenceIndex > 0 { try? await audio.seekToSentence(index: playback.sentenceIndex - 1) }
        else if passageIndex > 0 { await open(passageIndex: passageIndex - 1, offset: 0, autoplay: isPlaying, scroll: true) }
    }

    func repeatSentence() async {
        guard let playback else { return }
        try? await audio.seekToSentence(index: playback.sentenceIndex)
        if !isPlaying { try? await audio.play() }
    }

    func setSpeed(_ speed: Float) async {
        try? await LinguaShared.shared.setSpeechRate(rate: speed)
        try? await audio.setSpeed(speed: speed)
    }

    func setVoice(_ id: String?) async {
        guard let book else { return }
        try? await LinguaShared.shared.setVoice(languageTag: book.learningLanguage, voiceId: id)
        if let passage { try? await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: id, startOffset: playback?.resumeOffset ?? 0) }
    }

    /// «Leer desde aquí»: desde un offset del capítulo.
    func readFrom(chapterOffset: Int) async {
        guard let layout, let loc = layout.location(chapterOffset: chapterOffset) else { return }
        wordCard = nil
        if !ttsMode { try? await LinguaShared.shared.setTtsMode(enabled: true) }
        if loc.passageIndex != passageIndex {
            await open(passageIndex: loc.passageIndex, offset: loc.offset, autoplay: true, scroll: false)
        } else {
            try? await audio.seekToOffset(charOffset: Int32(loc.offset))
            try? await audio.play()
        }
    }

    // MARK: Toque en palabra

    func tapped(chapterOffset: Int?) async {
        guard let chapterOffset, let layout, let book, let loc = layout.location(chapterOffset: chapterOffset),
              loc.passageIndex < book.passages.count else {
            withAnimation { controlsVisible.toggle() }
            return
        }
        let passage = book.passages[loc.passageIndex]
        guard let token = OffsetIndex.shared.tokenAt(tokens: passage.tokens, offset: Int32(loc.offset)) else {
            withAnimation { controlsVisible.toggle() }
            return
        }
        var pausedByTap = false
        if isPlaying, prefs?.pauseOnWordTap ?? true { try? await audio.pause(); pausedByTap = true }
        do {
            let lookup = try await LinguaShared.shared.lookupWord(book: book, passage: passage, token: token, targetLanguage: translationLanguage)
            let saved = (try? await LinguaShared.shared.isSavedWord(bookId: book.id, term: token.text, targetLanguage: translationLanguage))?.boolValue ?? false
            wordCard = WordCard(passageIndex: loc.passageIndex, token: token, lookup: lookup, saved: saved, pausedByTap: pausedByTap)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func openSentenceCard(_ card: WordCard) async {
        guard let book else { return }
        let passage = book.passages[card.passageIndex]
        let translation = (card.lookup.sentenceTranslation as? TranslationResultTranslated)?.text
        sentenceCard = SentenceCard(passageIndex: card.passageIndex, sentence: card.lookup.sentence, translation: translation)
        _ = passage
    }

    func saveWord(_ card: WordCard) async {
        guard let book else { return }
        let translation = (card.lookup.word as? TranslationResultTranslated)?.text
        _ = try? await LinguaShared.shared.saveWord(
            bookId: book.id, bookTitle: book.title, term: card.token.text, lemma: card.lookup.wordLemma, translation: translation,
            sourceLanguage: book.learningLanguage, targetLanguage: translationLanguage, context: card.lookup.sentence.text
        )
        wordCard?.saved = true
    }

    func resumeAfterCard() async {
        let paused = wordCard?.pausedByTap ?? false
        wordCard = nil
        sentenceCard = nil
        if paused { try? await audio.play() }
    }

    func speak(_ text: String) async {
        guard let book else { return }
        try? await audio.speakSnippet(text: text, languageTag: book.learningLanguage)
    }

    // MARK: Marcadores, índice y búsqueda

    var currentPageBookmark: BookmarkEntity? {
        guard let layout, pageIndex < pageOffsets.count, let loc = layout.location(chapterOffset: pageOffsets[pageIndex]) else { return nil }
        let pageEnd = pageIndex + 1 < pageOffsets.count ? pageOffsets[pageIndex + 1] : Int.max
        return bookmarks.first { b in
            guard let off = layout.chapterOffset(passageIndex: Int(b.passageIndex), offset: Int(b.charOffset)) else { return false }
            return off >= pageOffsets[pageIndex] && off < pageEnd && Int(b.passageIndex) >= loc.passageIndex
        }
    }

    func toggleBookmark() async {
        if let existing = currentPageBookmark {
            try? await LinguaShared.shared.deleteBookmark(id: existing.id)
            return
        }
        guard let book, let layout, pageIndex < pageOffsets.count, let loc = layout.location(chapterOffset: pageOffsets[pageIndex]) else { return }
        let passage = book.passages[loc.passageIndex]
        let excerpt = String(ChapterLayout.utf16Slice(passage.text, loc.offset, min(passage.text.utf16.count, loc.offset + 80)).prefix(80))
        _ = try? await LinguaShared.shared.addBookmark(bookId: book.id, passageId: passage.id, passageIndex: Int32(loc.passageIndex), charOffset: Int32(loc.offset), excerpt: excerpt)
    }

    func jump(toPassage index: Int, offset: Int) async {
        await open(passageIndex: index, offset: offset, autoplay: isPlaying, scroll: true)
    }

    func jump(toChapter index: Int) async {
        guard let book, index < book.chapters.count, let first = book.chapters[index].passages.first else { return }
        await open(passageIndex: Int(first.index), offset: 0, autoplay: isPlaying, scroll: true)
    }

    func search(_ query: String) -> [SearchHit] {
        guard let book, query.count >= 2 else { return [] }
        let needle = query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        var hits: [SearchHit] = []
        for passage in book.passages {
            let text = passage.text
            let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            var searchRange = folded.startIndex..<folded.endIndex
            while let r = folded.range(of: needle, range: searchRange), hits.count < 200 {
                let offset = folded.utf16.distance(from: folded.utf16.startIndex, to: r.lowerBound)
                let start = max(0, offset - 40)
                let snippet = ChapterLayout.utf16Slice(text, start, min(text.utf16.count, offset + needle.utf16.count + 60))
                hits.append(SearchHit(passageIndex: Int(passage.index), offset: offset, chapterTitle: book.chapterOf(passage: passage)?.title ?? "", snippet: (start > 0 ? "…" : "") + snippet))
                searchRange = r.upperBound..<folded.endIndex
            }
        }
        return hits
    }

    // MARK: Progreso

    private func persist() async {
        guard let passage else { return }
        let offset = (playback?.passageId == passage.id && playback?.status != .completed) ? Int(playback?.resumeOffset ?? 0) : 0
        await persist(passage: passage, offset: offset)
    }

    private func persist(passage: Passage, offset: Int) async {
        guard let book else { return }
        try? await LinguaShared.shared.saveProgress(bookId: book.id, passageId: passage.id, passageIndex: Int32(passageIndex), totalPassages: Int32(book.passages.count), charOffset: Int32(offset))
    }

    // MARK: Pantalla de bloqueo

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        remoteTargets = [
            center.playCommand.addTarget { [weak self] _ in Task { await self?.togglePlay() }; return .success },
            center.pauseCommand.addTarget { [weak self] _ in Task { await self?.togglePlay() }; return .success },
            center.togglePlayPauseCommand.addTarget { [weak self] _ in Task { await self?.togglePlay() }; return .success },
            center.nextTrackCommand.addTarget { [weak self] _ in Task { await self?.nextSentence() }; return .success },
            center.previousTrackCommand.addTarget { [weak self] _ in Task { await self?.previousSentence() }; return .success },
        ]
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }

    private func updateNowPlaying() {
        guard let book else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: book.title,
            MPMediaItemPropertyArtist: chapter?.title ?? "",
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
    }
}
