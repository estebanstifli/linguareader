import SwiftUI
import LinguaShared

struct ReaderView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: ReaderModel
    @State private var sheet: ReaderSheet?

    init(bookId: String) {
        _model = StateObject(wrappedValue: ReaderModel(bookId: bookId))
    }

    private var theme: ReaderTheme { model.theme(systemDark: colorScheme == .dark) }

    var body: some View {
        ZStack {
            Color(theme.background).ignoresSafeArea()
            if let error = model.error, model.book == nil {
                Text(error).foregroundStyle(.red).padding()
            } else if model.layout == nil {
                ProgressView()
            } else {
                page
            }
            if model.controlsVisible { bars }
        }
        .statusBarHidden(!model.controlsVisible)
        .toolbar(.hidden, for: .navigationBar)
        .preferredColorScheme(theme.dark ? .dark : .light)
        .task { await model.start(store: store, systemDark: colorScheme == .dark) }
        .onChange(of: colorScheme) { _, new in model.onSystemDarkChanged(new == .dark) }
        .onChange(of: store.prefs) { _, _ in model.rebuildLayoutIfNeeded() }
        .onDisappear { Task { await model.stop() } }
        .sheet(item: $model.wordCard) { card in
            WordCardSheet(card: card, model: model)
                .presentationDetents(model.showFullCard ? [.large] : [.medium, .large])
        }
        .sheet(item: $model.sentenceCard) { card in SentenceCardSheet(card: card, model: model).presentationDetents([.medium, .large]) }
        .sheet(item: $sheet) { which in
            switch which {
            case .index: IndexSheet(model: model)
            case .search: SearchSheet(model: model)
            case .settings: ReaderSettingsSheet(model: model)
            case .about: AboutSheet(model: model)
            }
        }
    }

    private var page: some View {
        ChapterPageView(
            layout: model.layout,
            theme: theme,
            pageIndex: $model.pageIndex,
            highlight: model.highlight,
            showOffset: model.showOffset,
            onShowOffsetApplied: { model.showOffset = nil },
            onPages: { offsets in model.pagesResolved(offsets) },
            onTap: { offset in Task { await model.tapped(chapterOffset: offset) } }
        )
        .padding(.horizontal, 20)
        .padding(.top, model.controlsVisible ? 96 : 44)
        .padding(.bottom, model.controlsVisible ? (model.ttsMode ? 150 : 90) : 36)
        .accessibilityIdentifier("reader.text")
        .accessibilityLabel(model.passage?.text ?? "")
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 30).onEnded { value in
                if value.translation.width < -30 { model.nextPage() } else if value.translation.width > 30 { model.previousPage() }
            }
        )
    }

    private var bars: some View {
        VStack(spacing: 0) {
            topBar
            Spacer()
            bottomBar
        }
    }

    private var topBar: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 18) {
                Button { dismiss() } label: { Image(systemName: "chevron.left") }.accessibilityIdentifier("reader.back")
                Spacer()
                Button { sheet = .index } label: { Image(systemName: "list.bullet") }.accessibilityIdentifier("reader.index")
                Button { sheet = .search } label: { Image(systemName: "magnifyingglass") }
                Button { Task { try? await LinguaShared.shared.setTtsMode(enabled: !model.ttsMode) } } label: {
                    Image(systemName: model.ttsMode ? "speaker.wave.2" : "speaker.slash")
                }
                Button { sheet = .settings } label: { Image(systemName: "textformat.size") }.accessibilityIdentifier("reader.settings")
                Menu {
                    Button { sheet = .about } label: { Label("About this book", systemImage: "info.circle") }
                } label: { Image(systemName: "ellipsis") }
            }
            .font(.title3)
            Text(model.book?.title ?? "").font(.subheadline.bold()).lineLimit(1)
            Text("\(model.chapter?.title ?? "") · page \(model.pageIndex + 1) of \(model.pageCount)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 8)
        .background(.bar)
    }

    private var bottomBar: some View {
        VStack(spacing: 6) {
            if model.ttsMode {
                PlayerBar(model: model)
            }
            HStack {
                Text("\(model.pageIndex + 1) / \(model.pageCount)").font(.caption).monospacedDigit()
                if model.pageCount > 1 {
                    Slider(value: Binding(get: { Double(min(model.pageIndex, model.pageCount - 1)) }, set: { model.pageIndex = Int($0.rounded()) }), in: 0...Double(model.pageCount - 1), step: 1)
                } else {
                    Spacer()
                }
                Button { Task { await model.toggleBookmark() } } label: {
                    Image(systemName: model.currentPageBookmark != nil ? "bookmark.fill" : "bookmark")
                }
                .accessibilityIdentifier("reader.bookmark")
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(.bar)
    }
}

enum ReaderSheet: String, Identifiable {
    case index, search, settings, about
    var id: String { rawValue }
}

/// Reproductor compacto: voz, frase anterior, repetir, reproducir, siguiente, velocidad y estado de sincronía.
struct PlayerBar: View {
    @ObservedObject var model: ReaderModel
    private let speeds: [Float] = [0.5, 0.75, 0.9, 1.0, 1.25, 1.5]

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 22) {
                if model.voices.count > 1 {
                    Menu {
                        Button("Automatic") { Task { await model.setVoice(nil) } }
                        ForEach(model.voices, id: \.id) { v in
                            Button(v.displayName + (v.quality?.intValue ?? 0 >= 400 ? " · enhanced" : "")) { Task { await model.setVoice(v.id) } }
                        }
                    } label: { Image(systemName: "person.wave.2") }
                }
                Button { Task { await model.previousSentence() } } label: { Image(systemName: "backward.end") }.accessibilityIdentifier("reader.previous")
                Button { Task { await model.repeatSentence() } } label: { Image(systemName: "arrow.counterclockwise") }
                Button { Task { await model.togglePlay() } } label: {
                    Image(systemName: model.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 44))
                }
                .accessibilityIdentifier(model.isPlaying ? "reader.pause" : "reader.play")
                Button { Task { await model.nextSentence() } } label: { Image(systemName: "forward.end") }.accessibilityIdentifier("reader.next")
                Menu {
                    ForEach(speeds, id: \.self) { s in
                        Button(String(format: "%.2g×", s) + ((model.prefs?.speechRate ?? 1) == s ? " ✓" : "")) { Task { await model.setSpeed(s) } }
                    }
                } label: { Text(String(format: "%.2g×", model.prefs?.speechRate ?? 1)).font(.footnote.monospacedDigit()) }
            }
            .font(.title3)
            Text(status).font(.caption2).foregroundStyle(.secondary).accessibilityIdentifier("reader.status")
        }
    }

    private var status: String {
        guard let p = model.playback else { return "" }
        if p.readiness is EngineReadinessUnavailable { return "voice not available" }
        var parts = [p.status.name.lowercased()]
        if p.status == .error { parts.append("audio failed, see Diagnostics in reading settings") }
        if p.buffering { parts.append("generating audio…") }
        switch p.granularity {
        case .word: parts.append("word sync")
        case .sentence: parts.append("sentence sync")
        default: break
        }
        return parts.joined(separator: " · ")
    }
}
