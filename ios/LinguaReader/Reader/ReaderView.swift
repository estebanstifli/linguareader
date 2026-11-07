import SwiftUI
import LinguaShared

struct ReaderView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: ReaderModel
    @State private var sheet: ReaderSheet?
    @State private var shareItem: ShareItem?
    @State private var confirmTrash = false
    @State private var newCollection = false
    @State private var readerSize: CGSize = .zero

    /// `listen`: «Read and listen» desde la ficha: la voz arranca al abrir. `original`: abrir viendo el PDF original.
    init(bookId: String, listen: Bool = false, original: Bool = false) {
        _model = StateObject(wrappedValue: ReaderModel(bookId: bookId, listenOnOpen: listen, original: original))
    }

    private var theme: ReaderTheme { model.theme(systemDark: colorScheme == .dark) }

    var body: some View {
        ZStack {
            Color(theme.background).ignoresSafeArea()
            if let error = model.error, model.book == nil {
                Text(error).foregroundStyle(.red).padding()
            } else if model.layout == nil {
                ProgressView()
            } else if model.showOriginal {
                ReaderPdfView(model: model, theme: theme)
                    .padding(.top, model.controlsVisible ? 88 : 0)
                    .padding(.bottom, model.controlsVisible ? (model.ttsMode ? 110 : 0) : 0)
            } else {
                page
            }
            if model.preparing {
                PreparingOverlay(title: model.book?.title ?? "", reduceMotion: (model.prefs?.reduceMotion ?? false) || UIAccessibility.isReduceMotionEnabled, theme: theme)
            }
            if model.controlsVisible { bars }
        }
        .onGeometryChange(for: CGSize.self) { $0.size } action: { readerSize = $0 }
        .statusBarHidden(!model.controlsVisible)
        .toolbar(.hidden, for: .navigationBar)
        .preferredColorScheme(theme.dark ? .dark : .light)
        .task { await model.start(store: store, systemDark: colorScheme == .dark) }
        .onChange(of: colorScheme) { _, new in model.onSystemDarkChanged(new == .dark) }
        .onChange(of: store.prefs) { _, _ in model.rebuildLayoutIfNeeded() }
        .onDisappear { Task { await model.stop() } }
        .sheet(isPresented: $model.showFullCard, onDismiss: { if model.wordCard != nil { Task { await model.resumeAfterCard() } } }) {
            if let card = model.wordCard {
                WordCardSheet(card: card, model: model).presentationDetents([.medium, .large])
            }
        }
        .sheet(item: $model.sentenceCard, onDismiss: { Task { await model.resumeAfterCard() } }) { card in
            SentenceCardSheet(card: card, model: model).presentationDetents([.medium, .large])
        }
        .sheet(item: $sheet) { which in
            switch which {
            case .index: IndexSheet(model: model)
            case .search: SearchSheet(model: model)
            case .settings: ReaderSettingsSheet(model: model)
            case .about: AboutSheet(model: model)
            }
        }
        .sheet(item: $shareItem) { item in ShareSheet(items: [item.url]) }
        .sheet(isPresented: $newCollection) {
            NewCollectionSheet { name in store.createCollection(name, bookId: model.bookId) }
        }
        .confirmationDialog(Text("“\(model.book?.title ?? "")” will leave the library. You can restore it from the library's Trash."), isPresented: $confirmTrash, titleVisibility: .visible) {
            Button("Move to trash", role: .destructive) {
                if let book = model.book { store.trash(book.summary()) }
                dismiss()
            }
        }
    }

    /// Libro abierto (dos caras) en horizontal cuando cada cara tiene un ancho cómodo: iPad, y iPhone grande en
    /// horizontal. Misma regla que Android (`PagedReader.kt`: ≥ 840 de ancho y más ancho que alto).
    private var columns: Int {
        (model.prefs?.doublePage ?? true) && readerSize.width > readerSize.height && readerSize.width >= 840 ? 2 : 1
    }

    private var page: some View {
        ChapterPageView(
            layout: model.layout,
            theme: theme,
            columns: columns,
            pageIndex: $model.pageIndex,
            highlight: model.highlight,
            pointer: model.pointerPlan,
            playing: model.isPlaying,
            reduceMotion: (model.prefs?.reduceMotion ?? false) || UIAccessibility.isReduceMotionEnabled,
            showOffset: model.showOffset,
            anchor: model.wordCard?.anchor,
            onAnchorRect: { model.wordAnchorRect = $0 },
            onShowOffsetApplied: { model.showOffset = nil },
            onPages: { offsets in model.pagesResolved(offsets) },
            onTap: { offset in Task { await model.tapped(chapterOffset: offset) } },
            onLongPress: { offset in Task { await model.longPressed(chapterOffset: offset) } }
        )
        .overlay {
            GeometryReader { geo in
                if let card = model.wordCard, !model.showFullCard {
                    WordPopupView(card: card, anchor: model.wordAnchorRect, area: geo.size, model: model)
                }
            }
        }
        .padding(.horizontal, columns > 1 ? 32 : 20)
        .padding(.top, model.controlsVisible ? 96 : 44)
        .padding(.bottom, model.controlsVisible ? (model.ttsMode ? 150 : 90) : 36)
        .accessibilityIdentifier("reader.text")
        .accessibilityLabel(model.passage?.text ?? "")
        .accessibilityValue(columns > 1 ? String(localized: "Two pages") : "")
        .accessibilityAction(named: Text("Next page")) { model.nextPage() }
        .accessibilityAction(named: Text("Previous page")) { model.previousPage() }
        .accessibilityAction(named: Text("Show or hide the controls")) { withAnimation { model.controlsVisible.toggle() } }
        .accessibilityAction(named: Text("Translate sentence")) { Task { await model.translateCurrentSentence() } }
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
            banners
            Spacer()
            bottomBar
        }
    }

    /// Avisos bajo la barra superior: traductor que falta y problema de audio (como los banners de Android).
    @ViewBuilder
    private var banners: some View {
        let missing = model.missingTranslators
        if !missing.isEmpty {
            let names = missing.map { Languages.nameInSentence($0) }.formatted(.list(type: .and))
            let running = missing.contains { store.translationModels?.downloads[$0] is TranslationModelDownloadRunning }
            VStack(alignment: .leading, spacing: 4) {
                Text("To translate you need the \(names) translator (≈\(Int(40 * missing.count)) MB, downloaded once and works offline).").font(.footnote)
                if running { ProgressView() } else {
                    Button("Download") { model.downloadMissingTranslators() }.font(.footnote.bold())
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.accentColor.opacity(0.12))
            .accessibilityIdentifier("reader.translatorBanner")
        }
        if let problem = model.audioProblem {
            VStack(alignment: .leading, spacing: 4) {
                Text(problem).font(.footnote)
                HStack(spacing: 16) {
                    Button("Retry") { Task { await model.retryAudio() } }
                    Button("Open settings") { sheet = .settings }
                }
                .font(.footnote.bold())
            }
            .padding(.horizontal, 16).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.red.opacity(0.12))
            .accessibilityIdentifier("reader.audioBanner")
        }
    }

    private var topBar: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 18) {
                Button { dismiss() } label: { Image(systemName: "chevron.left") }.accessibilityIdentifier("reader.back").accessibilityLabel("Back")
                Spacer()
                Button { sheet = .index } label: { Image(systemName: "list.bullet") }.accessibilityIdentifier("reader.index").accessibilityLabel("Contents")
                Button { sheet = .search } label: { Image(systemName: "magnifyingglass") }.accessibilityLabel("Search in the book")
                Button { Task { try? await LinguaShared.shared.setTtsMode(enabled: !model.ttsMode) } } label: {
                    Image(systemName: model.ttsMode ? "speaker.wave.2" : "speaker.slash")
                }
                .accessibilityLabel(model.ttsMode ? Text("Turn the voice off") : Text("Turn the voice on"))
                if model.originalAvailable {
                    Button { model.toggleOriginal() } label: { Image(systemName: model.showOriginal ? "text.book.closed" : "doc.text.magnifyingglass") }
                        .accessibilityLabel(model.showOriginal ? Text("View as text") : Text("View the original PDF"))
                        .accessibilityIdentifier("reader.original")
                }
                Button { sheet = .settings } label: { Image(systemName: "textformat.size") }.accessibilityIdentifier("reader.settings").accessibilityLabel("Reading settings")
                overflowMenu
            }
            .font(.title3)
            Text(model.book?.title ?? "").font(.subheadline.bold()).lineLimit(1)
            Text("\(model.chapter?.title ?? "") · page \(model.pageIndex + 1) of \(model.pageCount)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 8)
        .background(Color(theme.background).opacity(0.96))
        .foregroundStyle(Color(theme.text))
    }

    /// Menú ⋮ como el de Android: compartir el texto, añadir a estanterías/colecciones, papelera y «Acerca del documento».
    private var overflowMenu: some View {
        Menu {
            Button { Task { shareItem = (await model.shareTextFile()).map(ShareItem.init) } } label: { Label("Share as text", systemImage: "square.and.arrow.up") }
            Menu {
                ForEach(store.shelves?.shelves ?? [], id: \.id) { shelf in
                    let member = shelf.bookIds.contains(model.bookId)
                    Button { store.setInShelf(shelf.id, model.bookId, !member) } label: {
                        Label(Shelves.title(shelf), systemImage: member ? "checkmark" : "")
                    }
                }
                Divider()
                Button { newCollection = true } label: { Label("New collection…", systemImage: "folder.badge.plus") }
            } label: { Label("Add to…", systemImage: "folder") }
            Button(role: .destructive) { confirmTrash = true } label: { Label("Move to trash", systemImage: "trash") }
            Divider()
            Button { sheet = .about } label: { Label("About this document", systemImage: "info.circle") }
        } label: { Image(systemName: "ellipsis") }
        .accessibilityLabel("More options")
        .accessibilityIdentifier("reader.menu")
    }

    private var bottomBar: some View {
        VStack(spacing: 6) {
            if model.ttsMode {
                PlayerBar(model: model)
            }
            if model.showOriginal {
                EmptyView()
            } else { HStack {
                Text("\(model.pageIndex + 1) / \(model.pageCount)").font(.caption).monospacedDigit()
                    .accessibilityIdentifier(columns > 1 ? "reader.pages.spread" : "reader.pages")
                if model.pageCount > 1 {
                    Slider(value: Binding(get: { Double(min(model.pageIndex, model.pageCount - 1)) }, set: { model.pageIndex = Int($0.rounded()) }), in: 0...Double(model.pageCount - 1), step: 1)
                        .accessibilityLabel("Page")
                } else {
                    Spacer()
                }
                Button { Task { await model.toggleBookmark() } } label: {
                    Image(systemName: model.currentPageBookmark != nil ? "bookmark.fill" : "bookmark")
                }
                .accessibilityIdentifier("reader.bookmark")
                .accessibilityLabel(model.currentPageBookmark != nil ? Text("Remove bookmark") : Text("Add bookmark"))
                if OrientationLock.isPhone, let prefs = store.prefs {
                    // Orientación de toda la app (vertical → horizontal → automática), como el botón de Android.
                    Button { Task { try? await LinguaShared.shared.cycleOrientation() } } label: {
                        Image(systemName: OrientationLock.icon(prefs.orientation.name))
                    }
                    .padding(.leading, 12)
                    .accessibilityIdentifier("reader.orientation")
                    .accessibilityLabel(Text("Screen orientation: \(OrientationLock.label(prefs.orientation.name))"))
                }
            } }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Color(theme.background).opacity(0.96))
        .foregroundStyle(Color(theme.text))
    }
}

enum ReaderSheet: String, Identifiable {
    case index, search, settings, about
    var id: String { rawValue }
}

struct ShareItem: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

/// Hoja de compartir del sistema (texto del libro como fichero .txt).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// «Preparing to read…»: cubre el lector mientras el motor genera las primeras frases (barras de sonido animadas,
/// quietas con «reducir movimiento»). Consume los toques para no dejar pulsar el texto aún no listo.
struct PreparingOverlay: View {
    let title: String
    let reduceMotion: Bool
    let theme: ReaderTheme
    @State private var phase = false

    var body: some View {
        ZStack {
            Color(theme.background).ignoresSafeArea()
            VStack(spacing: 12) {
                HStack(alignment: .center, spacing: 8) {
                    ForEach(0..<5, id: \.self) { i in
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color.accentColor)
                            .frame(width: 12, height: reduceMotion ? 36 : (phase ? CGFloat(18 + (i * 7) % 40) : CGFloat(60 - (i * 9) % 44)))
                            .animation(reduceMotion ? nil : .easeInOut(duration: 0.52 + Double(i) * 0.09).repeatForever(autoreverses: true), value: phase)
                    }
                }
                .frame(height: 72)
                Text("Preparing to read…").font(.title2.bold()).multilineTextAlignment(.center).padding(.top, 20)
                if !title.isEmpty { Text(title).font(.headline).foregroundStyle(.tint).multilineTextAlignment(.center) }
                Text("Generating the first sentences so the voice starts without gaps.")
                    .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            .padding(32)
            .foregroundStyle(Color(theme.text))
        }
        .contentShape(Rectangle())
        .onTapGesture {}
        .onAppear { if !reduceMotion { phase = true } }
        .accessibilityIdentifier("reader.preparing")
    }
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
                            Button(v.displayName + (v.quality?.intValue ?? 0 >= 400 ? " · " + String(localized: "enhanced") : "")) { Task { await model.setVoice(v.id) } }
                        }
                    } label: { Image(systemName: "person.wave.2") }
                    .accessibilityLabel("Voice")
                }
                Button { Task { await model.previousSentence() } } label: { Image(systemName: "backward.end") }.accessibilityIdentifier("reader.previous").accessibilityLabel("Previous sentence")
                Button { Task { await model.repeatSentence() } } label: { Image(systemName: "arrow.counterclockwise") }.accessibilityLabel("Repeat sentence")
                Button { Task { await model.togglePlay() } } label: {
                    Image(systemName: model.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 44))
                }
                .accessibilityIdentifier(model.isPlaying ? "reader.pause" : "reader.play")
                .accessibilityLabel(model.isPlaying ? Text("Pause") : Text("Play"))
                Button { Task { await model.nextSentence() } } label: { Image(systemName: "forward.end") }.accessibilityIdentifier("reader.next").accessibilityLabel("Next sentence")
                Menu {
                    ForEach(speeds, id: \.self) { s in
                        Button(String(format: "%.2g×", s) + ((model.prefs?.speechRate ?? 1) == s ? " ✓" : "")) { Task { await model.setSpeed(s) } }
                    }
                } label: { Text(String(format: "%.2g×", model.prefs?.speechRate ?? 1)).font(.footnote.monospacedDigit()) }
                .accessibilityLabel("Speed")
            }
            .font(.title3)
            Text(status).font(.caption2).foregroundStyle(.secondary).accessibilityIdentifier("reader.status")
        }
    }

    /// Estado del reproductor. El nombre del estado (playing, paused…) queda en inglés en minúsculas porque el test de
    /// humo lo busca; el resto se traduce.
    private var status: String {
        guard let p = model.playback else { return "" }
        if p.readiness is EngineReadinessUnavailable { return String(localized: "voice not available") }
        var parts = [p.status.name.lowercased()]
        if p.status == .error { parts.append(String(localized: "audio failed, see Diagnostics in reading settings")) }
        if p.buffering { parts.append(String(localized: "generating audio…")) }
        if model.narrationActive { parts.append(String(localized: "studio narration")) }
        switch p.granularity {
        case .word: parts.append(String(localized: "word sync"))
        case .sentence: parts.append(String(localized: "sentence sync"))
        default: break
        }
        return parts.joined(separator: " · ")
    }
}
