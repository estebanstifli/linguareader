import Foundation
import LinguaShared

/// Estado del lector: libro, pasaje actual y reproducción. El audio lo lleva el `ReaderAudioController`
/// compartido (voz del sistema de iOS con rangos de palabra); aquí solo se traduce su `PlaybackState` a SwiftUI.
@MainActor
final class ReaderModel: ObservableObject {
    @Published private(set) var book: Book?
    @Published private(set) var passageIndex: Int = 0
    @Published private(set) var playback: PlaybackState?
    @Published private(set) var error: String?

    private let bookId: String
    private var watcher: FlowWatcher?
    private var audio: ReaderAudioController { LinguaShared.shared.audio }

    init(bookId: String) {
        self.bookId = bookId
    }

    var passage: Passage? {
        guard let book, passageIndex < book.passages.count else { return nil }
        return book.passages[passageIndex]
    }

    var isPlaying: Bool { playback?.status == .playing }

    func start() async {
        if book != nil { return }
        watcher = LinguaShared.shared.watchPlayback { [weak self] state in
            self?.playback = state
        }
        do {
            book = try await LinguaShared.shared.books.loadBook(bookId: bookId)
            await loadCurrentPassage()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func stop() async {
        try? await audio.stop()
        watcher?.close()
        watcher = nil
    }

    func togglePlay() async {
        do {
            if isPlaying { try await audio.pause() } else { try await audio.play() }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func nextPassage() async { await move(by: 1) }
    func previousPassage() async { await move(by: -1) }

    private func move(by delta: Int) async {
        guard let book else { return }
        let target = passageIndex + delta
        guard target >= 0, target < book.passages.count else { return }
        passageIndex = target
        await loadCurrentPassage()
    }

    private func loadCurrentPassage() async {
        guard let book, let passage else { return }
        do {
            try await audio.load(passage: passage, languageTag: book.learningLanguage, voiceId: nil, startOffset: 0)
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Rango UTF-16 del texto que hay que resaltar: la palabra activa si hay tiempos por palabra; si no, la frase.
    var highlightRange: Range<Int>? {
        guard let passage, let playback else { return nil }
        switch playback.granularity {
        case .word:
            if let id = playback.activeTokenId, let token = passage.tokenById(id: id) {
                return Int(token.rangeStart)..<Int(token.rangeEnd)
            }
            return sentenceRange(passage, playback)
        case .sentence:
            return sentenceRange(passage, playback)
        default:
            return nil
        }
    }

    private func sentenceRange(_ passage: Passage, _ playback: PlaybackState) -> Range<Int>? {
        let index = Int(playback.sentenceIndex)
        guard index >= 0, index < passage.sentences.count else { return nil }
        let sentence = passage.sentences[index]
        return Int(sentence.rangeStart)..<Int(sentence.rangeEnd)
    }
}
