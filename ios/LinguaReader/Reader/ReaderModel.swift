import Foundation
import SwiftUI
import MediaPlayer
import PDFKit
import LinguaShared

/// Ficha de palabra abierta (mini o completa).
struct WordCard: Identifiable {
    let id = UUID()
    let passageIndex: Int
    let token: Token
    let lookup: WordLookup
    var saved: Bool
    var pausedByTap: Bool
    /// Rango de la palabra en el capítulo: la página devuelve su caja para colocar el popup junto a ella.
    var anchor: NSRange?
}

struct SentenceCard: Identifiable {
    let id = UUID()
    let passageIndex: Int
    let sentence: Sentence
    let translation: String?
    /// La traducción la hizo el traductor del dispositivo (no viene con el libro): se etiqueta «Machine translation».
    var machine: Bool = false
    var pausedByTap: Bool = false
}

/// Texto de los motivos por los que no hay traducción (`TranslationResult.Unavailable`), como `Labels.unavailable` en Android.
enum TranslationLabels {
    static func unavailable(_ result: TranslationResult?, target: String) -> String {
        guard let reason = (result as? TranslationResultUnavailable)?.reason else { return String(localized: "No translation available.") }
        if reason == TranslationResultReason.sameLanguage { return String(localized: "Same language as the book.") }
        if reason == TranslationResultReason.modelMissing { return String(localized: "The translator for this language is not downloaded yet.") }
        if reason == TranslationResultReason.languagePairUnsupported { return String(localized: "This language pair is not supported.") }
        if reason == TranslationResultReason.offline { return String(localized: "No connection: the translation needs internet.") }
        if reason == TranslationResultReason.engineError { return String(localized: "The translator failed. Try again.") }
        return String(localized: "No translation into \(Languages.nameInSentence(target)) for this word.")
    }

    /// Traducción hecha por el traductor del dispositivo (no por el glosario incluido en el libro).
    static func isMachine(_ result: TranslationResult?) -> Bool {
        guard let t = result as? TranslationResultTranslated else { return false }
        return t.providerId != "bundled"
    }
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
    @Published private(set) var engineId = "apple-tts"
    @Published var showOffset: Int?
    @Published var controlsVisible = true
    @Published var wordCard: WordCard?
    /// Caja de la palabra de `wordCard` en la página visible (coordenadas de la página); nil si no se ve.
    @Published var wordAnchorRect: CGRect?
    @Published var sentenceCard: SentenceCard?
    @Published var showFullCard = false
    @Published var error: String?
    /// Ilustraciones de los pasajes ya cargadas (índice global del pasaje).
    @Published private(set) var passageImages: [Int: UIImage] = [:]
    /// «Ver original»: el PDF del libro en lugar del texto paginado (misma voz, fichas y progreso).
    @Published var showOriginal = false
    @Published private(set) var originalAvailable = false
    @Published private(set) var pdfDocument: PDFDocument?
    @Published private(set) var pdfMissing = false
    @Published private(set) var pdfLayout: PdfWordLayout?
    @Published private(set) var pdfLayoutProgress: Double?
    @Published private(set) var pdfLayoutFailed = false
    @Published private(set) var pdfLowCoverage = false
    @Published var pdfPage = 0
    private var pdfLoading = false
    /// Tras tocar o pasar página a mano, la vista deja de seguir a la voz unos segundos.
    private var lastPdfTouch = Date.distantPast
    private var tokenPassage: [String: Int] = [:]
    private var loadingImages: Set<Int> = []
    @Published var translatorDownloading = false
    /// Traducciones automáticas (ML Kit, caché en Room) por id de frase, para «traducción bajo cada frase» en libros
    /// sin traducciones incluidas.
    @Published private(set) var machineTranslations: [String: String] = [:]
    private var translating: Task<Void, Never>?

    let bookId: String
    /// «Read and listen» desde la ficha: la voz arranca al abrir (y activa el modo con voz si estaba apagado).
    let listenOnOpen: Bool
    /// Se lee con la narración pregrabada del libro (voz de estudio con tiempos por palabra) en vez de la voz del sistema.
    @Published private(set) var narrationActive = false
    private var narrationRequested = false
    private var narrationPending = false
    private var watchers: [FlowWatcher] = []
    private var store: AppStore?
    private var completionHandledFor: String?
    private var remoteTargets: [Any] = []
    private var lastStyle: ChapterLayout.Style?
    private var systemDark = false
    /// Última palabra que sonó (para saber si la pausa es el final de su frase).
    private var lastSpoken: Token?

    private var audio: ReaderAudioController { LinguaShared.shared.audio }

    init(bookId: String, listenOnOpen: Bool = false, original: Bool = false) {
        self.bookId = bookId
        self.listenOnOpen = listenOnOpen
        self.showOriginal = original
    }

    var prefs: UserPreferences? { store?.prefs }
    var passage: Passage? { book.map { b in Int(passageIndex) < b.passages.count ? b.passages[passageIndex] : nil } ?? nil }
    var chapter: Chapter? { book.map { b in chapterIndex < b.chapters.count ? b.chapters[chapterIndex] : nil } ?? nil }
    var isPlaying: Bool { playback?.status == .playing }
    var ttsMode: Bool { prefs?.ttsMode ?? true }
    /// Como en Android: abrir un libro para leer no prepara voz; solo se genera audio cuando el usuario lo pide
    /// (reproducir, «leer desde aquí»). Pasar páginas antes de eso no sintetiza ni precarga nada.
    private var audioRequested = false
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
            LinguaShared.shared.watchSelectedEngine { [weak self] id in self?.engineId = id },
            LinguaShared.shared.watchNarrationReady { [weak self] id in self?.narrationBecameReady(id) },
        ]
        do {
            let loaded = try await LinguaShared.shared.books.loadBook(bookId: bookId)
            book = loaded
            for passage in loaded.passages { for token in passage.tokens { tokenPassage[token.id] = Int(passage.index) } }
            originalAvailable = ((try? await LinguaShared.shared.originalPdfPath(bookId: bookId)) ?? nil) != nil
            if !originalAvailable { showOriginal = false }
            if showOriginal { await loadPdf() }
            // Como «leer desde aquí»: escuchar desde la ficha activa la lectura en voz alta.
            if listenOnOpen && !ttsMode {
                try? await LinguaShared.shared.setTtsMode(enabled: true)
                for _ in 0..<20 where !ttsMode { try? await Task.sleep(for: .milliseconds(50)) }
            }
            // Narración pregrabada ya descargada: se lee con ella en lugar de la voz del sistema.
            narrationActive = (try? await LinguaShared.shared.applyNarration(book: loaded, download: false))?.boolValue ?? false
            let progress = try? await LinguaShared.shared.loadProgress(bookId: bookId)
            let index = progress.map { Int($0.passageIndex) }.flatMap { $0 < loaded.passages.count ? $0 : nil } ?? 0
            let offset = progress.map { Int($0.charOffset) } ?? 0
            await open(passageIndex: index, offset: offset, autoplay: listenOnOpen, scroll: true)
            setupRemoteCommands()
            voices = (try? await LinguaShared.shared.listVoices(languageTag: loaded.learningLanguage)) ?? []
        } catch {
            self.error = error.localizedDescription
        }
    }

    func stop() async {
        translating?.cancel()
        await persist()
        // Solo si el audio sigue siendo el de este libro: al cambiar de libro, el lector nuevo ya cargó su pasaje.
        if let passage, playback == nil || playback?.passageId == passage.id { try? await audio.stop() }
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
            if s.showTranslations { s.machineTranslations = machineTranslations }
            s.themeId = p.theme.name
            if p.showIllustrations, let book, chapterIndex < book.chapters.count {
                let inChapter = Set(book.chapters[chapterIndex].passages.map { Int($0.index) })
                s.illustratedPassages = passageImages.keys.filter { inChapter.contains($0) }.sorted()
            }
        }
        s.themeId += systemDark ? "-dark" : "-light"
        return s
    }

    func rebuildLayoutIfNeeded() {
        guard let book, chapterIndex < book.chapters.count else { return }
        ensureMachineTranslations()
        ensureIllustrations()
        let style = self.style
        if layout != nil, layout?.chapterIndex == chapterIndex, lastStyle == style { return }
        lastStyle = style
        let anchor = currentReadingOffset()
        layout = ChapterLayout.build(book: book, chapterIndex: chapterIndex, style: style, theme: theme(systemDark: systemDark), images: passageImages)
        if let anchor, let offset = layout?.chapterOffset(passageIndex: anchor.passageIndex, offset: anchor.offset) { showOffset = offset }
        UIApplication.shared.isIdleTimerDisabled = prefs?.keepScreenOn ?? false
    }

    /// Traduce las frases del capítulo que no traen traducción en el libro (una vez por frase; Room las guarda) y
    /// recompone el capítulo al terminar. Si falta el modelo, pide su descarga y lo deja para la próxima vez.
    private func ensureMachineTranslations() {
        guard let book, prefs?.displayMode == .translationBelow, chapterIndex < book.chapters.count, translating == nil else { return }
        let language = Languages.primary(translationLanguage)
        let pending = book.chapters[chapterIndex].passages.flatMap { $0.sentences }
            .filter { $0.translations[language] == nil && $0.translations[translationLanguage] == nil && machineTranslations[$0.id] == nil }
        guard !pending.isEmpty else { return }
        translating = Task { [weak self] in
            var done: [String: String] = [:]
            for sentence in pending {
                if Task.isCancelled { break }
                guard let result = try? await LinguaShared.shared.translateText(text: sentence.text, sourceLanguage: book.learningLanguage, targetLanguage: language) else { continue }
                if let translated = result as? TranslationResultTranslated {
                    done[sentence.id] = translated.text
                } else if let unavailable = result as? TranslationResultUnavailable, unavailable.reason == .modelMissing {
                    LinguaShared.shared.downloadTranslationModelsFor(sourceLanguage: book.learningLanguage, targetLanguage: language)
                    break
                }
            }
            guard let self, !Task.isCancelled else { return }
            self.translating = nil
            if !done.isEmpty {
                self.machineTranslations.merge(done) { _, new in new }
                self.rebuildLayoutIfNeeded()
            }
        }
    }

    /// Carga (una vez) las ilustraciones de los pasajes del capítulo y recompone cuando llegan. Verificadas por sha256
    /// como la portada; una imagen corrupta o ausente simplemente no se muestra.
    private func ensureIllustrations() {
        guard let book, prefs?.showIllustrations ?? true, chapterIndex < book.chapters.count else { return }
        for passage in book.chapters[chapterIndex].passages {
            let index = Int(passage.index)
            guard let ref = passage.image, passageImages[index] == nil, !loadingImages.contains(index) else { continue }
            loadingImages.insert(index)
            Task { [weak self] in
                let data = try? await LinguaShared.shared.loadCover(bookId: book.id, cover: ref)
                let image = data.flatMap { UIImage(data: $0) }?.preparingThumbnail(of: CGSize(width: 1200, height: 1200))
                    ?? data.flatMap { UIImage(data: $0) }
                guard let self else { return }
                self.loadingImages.remove(index)
                if let image {
                    self.passageImages[index] = image
                    self.rebuildLayoutIfNeeded()
                }
            }
        }
    }

    /// Páginas (o pliegos) nuevas del capítulo. Si se volvió a cortar el mismo capítulo (giro, doble página, letra),
    /// la página visible sigue siendo la que contiene el texto que se veía.
    func pagesResolved(_ offsets: [Int]) {
        let visible = pageIndex < pageOffsets.count ? pageOffsets[pageIndex] : nil
        pageOffsets = offsets
        if let showOffset, let page = page(forChapterOffset: showOffset) {
            pageIndex = page
        } else if let visible, let page = page(forChapterOffset: visible) {
            pageIndex = page
        }
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
        guard let layout, pageIndex < pageOffsets.count else { return passage.map { _ in (passageIndex, 0) } }
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
        ensureMachineTranslations()
        ensureIllustrations()
        layout = ChapterLayout.build(book: book, chapterIndex: index, style: style, theme: theme(systemDark: systemDark), images: passageImages)
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
        lastSpoken = nil
        dismissWordCard()
        sentenceCard = nil
        if let chapter = book.chapterOf(passage: passage), let ci = book.chapters.firstIndex(where: { $0.id == chapter.id }) {
            chapterIndex = ci
        }
        if layout == nil || layout?.chapterIndex != chapterIndex {
            lastStyle = nil
            pageOffsets = []
            rebuildLayoutIfNeeded()
        }
        if scroll, let target = layout?.chapterOffset(passageIndex: index, offset: offset) { showOffset = target }
        do {
            if autoplay && ttsMode {
                audioRequested = true
                await requestNarration(book)
            }
            try await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, startOffset: Int32(offset))
            await persist(passage: passage, offset: offset)
            if autoplay && ttsMode {
                if !continuation { _ = try? await audio.warmUp(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, policy: WarmUpPolicy(targetDurationMs: 15_000, maxSentences: 1), fromSentence: 0) }
                try await audio.play()
            }
            if audioRequested && ttsMode {
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

    /// Al pedir audio: si el libro trae narración y no está descargada, la baja en segundo plano (una vez por sesión);
    /// mientras tanto, o si falla, se lee con la voz del sistema y se cambia a ella en la siguiente pausa.
    private func requestNarration(_ book: Book) async {
        if narrationRequested || narrationActive { return }
        narrationRequested = true
        narrationActive = (try? await LinguaShared.shared.applyNarration(book: book, download: true))?.boolValue ?? false
    }

    private func narrationBecameReady(_ id: String) {
        guard id == bookId, !narrationActive else { return }
        if isPlaying { narrationPending = true } else { Task { await switchToNarration() } }
    }

    /// Cambia a la narración ya descargada en el mismo punto (como `retryAudio` tras `awaitNarration` en Android).
    private func switchToNarration() async {
        narrationPending = false
        guard let book, let passage else { return }
        let offset = Int32(playback?.resumeOffset ?? 0)
        narrationActive = (try? await LinguaShared.shared.applyNarration(book: book, download: false))?.boolValue ?? false
        guard narrationActive else { return }
        try? await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, startOffset: offset)
    }

    private func onPlayback(_ state: PlaybackState) {
        playback = state
        if narrationPending, state.status != .playing { Task { await switchToNarration() } }
        guard let passage, state.passageId == passage.id else { return }
        if let id = state.activeTokenId, let token = passage.tokenById(id: id) { lastSpoken = token }
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
        if showOriginal, state.status == .playing, prefs?.autoScroll ?? true, Date().timeIntervalSince(lastPdfTouch) > 4,
           let id = state.activeTokenId, let layout = pdfLayout {
            let page = Int(layout.pageOf(tokenId: id))
            if page >= 0, page != pdfPage { pdfPage = page }
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

    /// Palabra que señala la mano (`HandPointerView`): la que suena; en el silencio tras una frase, la primera de la
    /// frase siguiente (o del pasaje siguiente); antes de la primera palabra de una frase, su comienzo. Solo con
    /// tiempos por palabra. Misma lógica que `pointerPlan` en el lector Android.
    var pointerPlan: PointerPlan? {
        guard prefs?.fingerPointerEnabled ?? true, let playback, let passage, let layout, let book,
              playback.passageId == passage.id, playback.granularity == .word,
              playback.status == .playing || playback.status == .paused else { return nil }
        func range(_ index: Int, _ token: Token) -> NSRange? {
            layout.chapterRange(passageIndex: index, start: Int(token.rangeStart), end: Int(token.rangeEnd))
        }
        if let id = playback.activeTokenId, let active = passage.tokenById(id: id) {
            guard let word = range(passageIndex, active) else { return nil }
            let position = passage.tokens.firstIndex { $0.id == active.id } ?? -1
            let next = position >= 0 && position + 1 < passage.tokens.count ? passage.tokens[position + 1] : nil
            return PointerPlan(word: word, lead: next.flatMap { range(passageIndex, $0) }, step: PointerStep(tokenId: active.id, gap: false))
        }
        guard Int(playback.sentenceIndex) < passage.sentences.count else { return nil }
        let sentence = passage.sentences[Int(playback.sentenceIndex)]
        let finished = lastSpoken?.sentenceId == sentence.id
        let index: Int
        let target: Token?
        if finished {
            if Int(sentence.index) + 1 < passage.sentences.count {
                let nextSentence = passage.sentences[Int(sentence.index) + 1]
                index = passageIndex
                target = passage.tokens.first { $0.sentenceId == nextSentence.id }
            } else {
                index = passageIndex + 1
                target = index < book.passages.count ? book.passages[index].tokens.first : nil
            }
        } else {
            index = passageIndex
            target = passage.tokens.first { $0.sentenceId == sentence.id }
        }
        guard let target, let word = range(index, target) else { return nil }
        return PointerPlan(word: word, lead: nil, step: PointerStep(tokenId: target.id, gap: true))
    }

    // MARK: PDF original

    func toggleOriginal() {
        guard originalAvailable else { return }
        showOriginal.toggle()
        if showOriginal { Task { await loadPdf() } }
    }

    /// Abre el PDF y calcula (una vez por sesión) las cajas de los tokens sobre sus páginas.
    func loadPdf() async {
        guard pdfDocument == nil, !pdfLoading else { return }
        pdfLoading = true
        defer { pdfLoading = false }
        guard let path = (try? await LinguaShared.shared.originalPdfPath(bookId: bookId)) ?? nil, let document = PDFDocument(url: URL(fileURLWithPath: path)) else {
            pdfMissing = true
            return
        }
        if document.isLocked { _ = document.unlock(withPassword: "") }
        pdfDocument = document
        pdfLayoutProgress = 0
        let layout = try? await LinguaShared.shared.pdfWordLayout(bookId: bookId) { [weak self] done, total in
            Task { @MainActor in self?.pdfLayoutProgress = total.intValue > 0 ? Double(done.intValue) / Double(total.intValue) : 0 }
        }
        pdfLayoutProgress = nil
        if let layout, let book {
            pdfLayout = layout
            pdfLowCoverage = layout.coverage(book: book) < 0.85
            pdfLayoutFailed = layout.tokenCount == 0
        } else {
            pdfLayoutFailed = true
        }
        if let page = currentPdfPage() { pdfPage = page }
    }

    /// Página donde está el punto de lectura actual (la voz o la primera palabra del pasaje).
    private func currentPdfPage() -> Int? {
        guard let layout = pdfLayout, let passage else { return nil }
        if let id = playback?.activeTokenId, playback?.passageId == passage.id { let p = Int(layout.pageOf(tokenId: id)); if p >= 0 { return p } }
        if let first = passage.tokens.first { let p = Int(layout.pageOf(tokenId: first.id)); if p >= 0 { return p } }
        return nil
    }

    /// Cajas a pintar sobre el PDF: la frase que suena en claro y la palabra activa en fuerte.
    var pdfHighlights: [PdfHighlight] {
        guard showOriginal, let layout = pdfLayout, let playback, let passage, playback.passageId == passage.id,
              playback.status == .playing || playback.status == .paused || playback.status == .ready else { return [] }
        let theme = theme(systemDark: systemDark)
        var out: [PdfHighlight] = []
        if playback.granularity != .none, Int(playback.sentenceIndex) < passage.sentences.count {
            let sentence = passage.sentences[Int(playback.sentenceIndex)]
            for token in passage.tokens where token.sentenceId == sentence.id {
                for b in layout.boxes(tokenId: token.id) {
                    out.append(PdfHighlight(page: Int(b.page), left: CGFloat(b.left), top: CGFloat(b.top), right: CGFloat(b.right), bottom: CGFloat(b.bottom), color: theme.sentenceHighlight, rounded: false))
                }
            }
        }
        if playback.granularity == .word, let id = playback.activeTokenId {
            for b in layout.boxes(tokenId: id) {
                out.append(PdfHighlight(page: Int(b.page), left: CGFloat(b.left), top: CGFloat(b.top), right: CGFloat(b.right), bottom: CGFloat(b.bottom), color: theme.wordHighlight.withAlphaComponent(0.6), rounded: true))
            }
        }
        return out
    }

    /// Toque sobre el PDF: la palabra bajo el dedo abre su ficha; fuera del texto, muestra u oculta los controles.
    func pdfTapped(page: Int, x: CGFloat, y: CGFloat) async {
        lastPdfTouch = Date()
        if wordCard != nil, !showFullCard { await resumeAfterCard(); return }
        guard page >= 0, let layout = pdfLayout, let book,
              let tokenId = layout.hit(page: Int32(page), x: Float(x), y: Float(y), slopX: 0.01, slopY: 0.006),
              let index = tokenPassage[tokenId], index < book.passages.count,
              let token = book.passages[index].tokenById(id: tokenId) else {
            withAnimation { controlsVisible.toggle() }
            return
        }
        await showWord(passageIndex: index, token: token, anchor: nil)
    }

    /// El usuario pasó a otra página con la lectura parada: el punto de lectura pasa a la primera palabra de esa página.
    func pdfUserScrolled(_ page: Int) {
        lastPdfTouch = Date()
        guard !isPlaying, let layout = pdfLayout, let book, let tokenId = layout.firstTokenOn(page: Int32(page)),
              let index = tokenPassage[tokenId], index < book.passages.count, let token = book.passages[index].tokenById(id: tokenId) else { return }
        if index != passageIndex { Task { await open(passageIndex: index, offset: Int(token.rangeStart), autoplay: false, scroll: false) } }
    }

    /// «Preparing to read…»: el motor genera las primeras frases tras pedir audio (audio en caché o narración).
    var preparing: Bool {
        guard audioRequested, let playback, playback.buffering else { return false }
        return playback.status != .playing && playback.status != .error
    }

    /// Error de audio que merece banner (como `AudioBanner` en Android): voz no disponible o motor caído.
    var audioProblem: String? {
        guard ttsMode, let playback else { return nil }
        if let unavailable = playback.readiness as? EngineReadinessUnavailable {
            let lang = Languages.nameInSentence(unavailable.languageTag)
            return String(localized: "No voice available for \(lang) on this device.")
        }
        if playback.status == .error {
            let code = playback.errorCode.map { "\($0.intValue)" } ?? "?"
            return String(localized: "The speech engine failed (code \(code)). Press play to try again.")
        }
        return nil
    }

    /// Vuelve a cargar el pasaje en el mismo punto y reproduce (botón «Retry» del banner de error).
    func retryAudio() async {
        guard let book, let passage else { return }
        let offset = playback?.passageId == passage.id ? (playback?.resumeOffset ?? 0) : 0
        audioRequested = true
        do {
            try await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, startOffset: offset)
            try await audio.play()
        } catch { self.error = error.localizedDescription }
    }

    /// Códigos de traductor que faltan para traducir del idioma del libro al del usuario (banner del lector).
    var missingTranslators: [String] {
        guard let book, let models = store?.translationModels, let store else { return [] }
        let native = LinguaShared.shared.translationCode(languageTag: translationLanguage) ?? Languages.primary(translationLanguage)
        return store.neededModels(Languages.primary(book.learningLanguage), native: native).filter { !models.downloaded.contains($0) }
    }

    func downloadMissingTranslators() {
        guard let book else { return }
        LinguaShared.shared.downloadTranslationModelsFor(sourceLanguage: book.learningLanguage, targetLanguage: translationLanguage)
    }

    func togglePlay() async {
        guard ttsMode else { try? await LinguaShared.shared.setTtsMode(enabled: true); return }
        do {
            if isPlaying { try await audio.pause(); await persist() } else {
                audioRequested = true
                let hadNarration = narrationActive
                if let book { await requestNarration(book) }
                if let passage, let book, playback?.passageId != passage.id || narrationActive != hadNarration {
                    let offset = playback?.passageId == passage.id ? (playback?.resumeOffset ?? 0) : 0
                    try await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, startOffset: offset)
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

    /// Cambia entre lectura en directo y audio en caché; recarga el pasaje en el controlador nuevo.
    func selectEngine(_ id: String) async {
        guard id != engineId else { return }
        let wasPlaying = isPlaying
        try? await audio.pause()
        try? await LinguaShared.shared.selectEngine(id: id)
        if let book, let passage {
            try? await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, startOffset: playback?.resumeOffset ?? 0)
            if wasPlaying { try? await audio.play() }
        }
    }

    func setSpeed(_ speed: Float) async {
        try? await LinguaShared.shared.setSpeechRate(rate: speed)
        try? await audio.setSpeed(speed: speed)
    }

    /// Cambia la voz y sigue leyendo donde estaba: recargar el pasaje deja el audio en «ready», así que si sonaba se
    /// vuelve a dar al play (como al cambiar de modo de audio).
    func setVoice(_ id: String?) async {
        guard let book else { return }
        let wasPlaying = isPlaying
        if wasPlaying { try? await audio.pause() }
        try? await LinguaShared.shared.setVoice(languageTag: book.learningLanguage, voiceId: id)
        guard let passage else { return }
        try? await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: id, startOffset: playback?.resumeOffset ?? 0)
        if wasPlaying { try? await audio.play() }
    }

    /// «Leer desde aquí»: desde un offset del capítulo.
    func readFrom(chapterOffset: Int) async {
        guard let layout, let loc = layout.location(chapterOffset: chapterOffset) else { return }
        dismissWordCard()
        audioRequested = true
        if !ttsMode { try? await LinguaShared.shared.setTtsMode(enabled: true) }
        let hadNarration = narrationActive
        if let book { await requestNarration(book) }
        if loc.passageIndex != passageIndex {
            await open(passageIndex: loc.passageIndex, offset: loc.offset, autoplay: true, scroll: false)
        } else if narrationActive != hadNarration, let passage, let book {
            try? await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: voiceId, startOffset: Int32(loc.offset))
            try? await audio.play()
        } else {
            try? await audio.seekToOffset(charOffset: Int32(loc.offset))
            try? await audio.play()
        }
    }

    // MARK: Toque en palabra

    func tapped(chapterOffset: Int?) async {
        // Con el popup abierto, el toque en la página lo cierra (y reanuda si la voz se pausó por él), como en Android.
        if wordCard != nil, !showFullCard { await resumeAfterCard(); return }
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
        let anchor = layout.chapterRange(passageIndex: loc.passageIndex, start: Int(token.rangeStart), end: Int(token.rangeEnd))
        await showWord(passageIndex: loc.passageIndex, token: token, anchor: anchor)
    }

    /// Ficha de una palabra (popup; sin ancla, en el PDF, la hoja completa): pausa si procede y consulta la traducción.
    private func showWord(passageIndex index: Int, token: Token, anchor: NSRange?) async {
        guard let book, index < book.passages.count else { return }
        let passage = book.passages[index]
        var pausedByTap = false
        if isPlaying, prefs?.pauseOnWordTap ?? true { try? await audio.pause(); pausedByTap = true }
        do {
            let lookup = try await LinguaShared.shared.lookupWord(book: book, passage: passage, token: token, targetLanguage: translationLanguage)
            let saved = (try? await LinguaShared.shared.isSavedWord(bookId: book.id, term: token.text, targetLanguage: translationLanguage))?.boolValue ?? false
            showFullCard = anchor == nil
            wordAnchorRect = nil
            wordCard = WordCard(passageIndex: index, token: token, lookup: lookup, saved: saved, pausedByTap: pausedByTap, anchor: anchor)
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Pulsación larga: hoja de la frase bajo el dedo, con su traducción (incluida en el libro o automática).
    func longPressed(chapterOffset: Int?) async {
        guard let chapterOffset, let layout, let book, let loc = layout.location(chapterOffset: chapterOffset),
              loc.passageIndex < book.passages.count else { return }
        let passage = book.passages[loc.passageIndex]
        guard let sentence = OffsetIndex.shared.sentenceAt(sentences: passage.sentences, offset: Int32(loc.offset))
            ?? passage.sentences.last(where: { Int($0.rangeStart) <= loc.offset }) else { return }
        await openSentence(passageIndex: loc.passageIndex, sentence: sentence)
    }

    /// Acción de accesibilidad «Translate sentence»: la que suena o, en pausa, la primera de la página.
    func translateCurrentSentence() async {
        guard let book, let passage else { return }
        if let playback, playback.passageId == passage.id, playback.granularity != .none, Int(playback.sentenceIndex) < passage.sentences.count {
            await openSentence(passageIndex: passageIndex, sentence: passage.sentences[Int(playback.sentenceIndex)])
            return
        }
        guard let layout, pageIndex < pageOffsets.count, let loc = layout.location(chapterOffset: pageOffsets[pageIndex]),
              loc.passageIndex < book.passages.count else { return }
        let p = book.passages[loc.passageIndex]
        guard let sentence = OffsetIndex.shared.sentenceAt(sentences: p.sentences, offset: Int32(loc.offset)) ?? p.sentences.first(where: { Int($0.rangeStart) >= loc.offset }) else { return }
        await openSentence(passageIndex: loc.passageIndex, sentence: sentence)
    }

    private func openSentence(passageIndex index: Int, sentence: Sentence) async {
        guard let book else { return }
        let passage = book.passages[index]
        var pausedByTap = false
        if isPlaying, prefs?.pauseOnWordTap ?? true { try? await audio.pause(); pausedByTap = true }
        wordCard = nil
        let language = Languages.primary(translationLanguage)
        if let included = sentence.translations[language] ?? sentence.translations[translationLanguage] {
            sentenceCard = SentenceCard(passageIndex: index, sentence: sentence, translation: included, machine: false, pausedByTap: pausedByTap)
            return
        }
        let result = try? await LinguaShared.shared.translateSentence(book: book, passage: passage, sentence: sentence, targetLanguage: translationLanguage)
        let translation = (result as? TranslationResultTranslated)?.text
        sentenceCard = SentenceCard(passageIndex: index, sentence: sentence, translation: translation, machine: TranslationLabels.isMachine(result), pausedByTap: pausedByTap)
    }

    func openSentenceCard(_ card: WordCard) async {
        let result = card.lookup.sentenceTranslation
        let translation = (result as? TranslationResultTranslated)?.text
        showFullCard = false
        wordCard = nil
        sentenceCard = SentenceCard(passageIndex: card.passageIndex, sentence: card.lookup.sentence, translation: translation, machine: TranslationLabels.isMachine(result), pausedByTap: card.pausedByTap)
    }

    /// Reproduce la frase de la hoja con la voz del libro, sin mover el punto de lectura.
    func speakSentence(_ card: SentenceCard) async { await speak(card.sentence.text) }

    // MARK: Menú del lector (compartir, estanterías, papelera)

    /// Texto plano del libro en un fichero temporal para la hoja de compartir del sistema.
    func shareTextFile() async -> URL? {
        guard let book, let text = try? await LinguaShared.shared.bookText(bookId: book.id) else { return nil }
        let name = book.title.replacingOccurrences(of: "/", with: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(name).txt")
        try? text.write(to: url, atomically: true, encoding: .utf8)
        return url
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

    /// Descarga los modelos que faltan para el libro y el idioma del usuario y vuelve a consultar la palabra de la ficha.
    func downloadTranslators(_ card: WordCard) async {
        guard let book, card.passageIndex < book.passages.count else { return }
        translatorDownloading = true
        defer { translatorDownloading = false }
        let ready = (try? await LinguaShared.shared.ensureTranslationModels(sourceLanguage: book.learningLanguage, targetLanguage: translationLanguage))?.boolValue ?? false
        guard ready else { error = "The translator could not be downloaded. Check the connection (or the Wi-Fi only setting) and try again."; return }
        guard wordCard?.token.id == card.token.id,
              let lookup = try? await LinguaShared.shared.lookupWord(book: book, passage: book.passages[card.passageIndex], token: card.token, targetLanguage: translationLanguage) else { return }
        wordCard = WordCard(passageIndex: card.passageIndex, token: card.token, lookup: lookup, saved: card.saved, pausedByTap: card.pausedByTap)
    }

    func resumeAfterCard() async {
        let paused = (wordCard?.pausedByTap ?? false) || (sentenceCard?.pausedByTap ?? false)
        wordCard = nil
        sentenceCard = nil
        showFullCard = false
        wordAnchorRect = nil
        if paused { try? await audio.play() }
    }

    /// Cierra la ficha de palabra sin reanudar (al pasar a otra acción, p. ej. «Read from here»).
    func dismissWordCard() {
        wordCard = nil
        showFullCard = false
        wordAnchorRect = nil
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
