import SwiftUI
import LinguaShared

struct ReaderView: View {
    @StateObject private var model: ReaderModel

    init(bookId: String) {
        _model = StateObject(wrappedValue: ReaderModel(bookId: bookId))
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                if let passage = model.passage {
                    Text(highlighted(passage.text, range: model.highlightRange))
                        .font(.system(.title3, design: .serif))
                        .lineSpacing(6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                        .accessibilityIdentifier("reader.text")
                } else if let error = model.error {
                    Text(error).foregroundStyle(.red).padding()
                } else {
                    ProgressView().padding()
                }
            }
            Divider()
            controls
        }
        .navigationTitle(model.book?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.start() }
        .onDisappear { Task { await model.stop() } }
    }

    private var controls: some View {
        HStack(spacing: 24) {
            Button { Task { await model.previousPassage() } } label: { Image(systemName: "backward.fill") }
                .accessibilityIdentifier("reader.previous")
            Button { Task { await model.togglePlay() } } label: {
                Image(systemName: model.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 44))
            }
            .accessibilityIdentifier(model.isPlaying ? "reader.pause" : "reader.play")
            Button { Task { await model.nextPassage() } } label: { Image(systemName: "forward.fill") }
                .accessibilityIdentifier("reader.next")
            Spacer()
            VStack(alignment: .trailing) {
                Text(statusLine).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("reader.status")
                if let book = model.book {
                    Text("\(model.passageIndex + 1) / \(book.passages.count)").font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .padding()
    }

    private var statusLine: String {
        guard let playback = model.playback else { return "" }
        let granularity: String
        switch playback.granularity {
        case .word: granularity = "word sync"
        case .sentence: granularity = "sentence"
        default: granularity = ""
        }
        return [playback.status.name.lowercased(), granularity].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// Resalta un rango UTF-16 del texto (los offsets de los tokens compartidos son UTF-16, como en Kotlin/JVM).
    private func highlighted(_ text: String, range: Range<Int>?) -> AttributedString {
        guard let range, range.lowerBound >= 0, range.upperBound <= text.utf16.count, range.lowerBound < range.upperBound else {
            return AttributedString(text)
        }
        let utf16 = text.utf16
        let start = utf16.index(utf16.startIndex, offsetBy: range.lowerBound)
        let end = utf16.index(utf16.startIndex, offsetBy: range.upperBound)
        var before = AttributedString(String(text[..<start]))
        var middle = AttributedString(String(text[start..<end]))
        let after = AttributedString(String(text[end...]))
        middle.backgroundColor = .yellow.opacity(0.6)
        before.append(middle)
        before.append(after)
        return before
    }
}
